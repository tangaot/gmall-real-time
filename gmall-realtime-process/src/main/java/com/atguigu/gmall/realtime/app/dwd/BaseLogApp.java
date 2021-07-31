package com.atguigu.gmall.realtime.app.dwd;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.atguigu.gmall.realtime.utils.MyKafkaUtil;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;

import java.text.SimpleDateFormat;

/**
 * Author: Ao Tang
 * Date: 2021/7/30
 * Desc: 对日志数据进行分流操作
 *  启动日志、曝光日志、页面日志
 *  启动日志放到启动侧输出流中
 *  曝光日志放到曝光侧输出流中
 *  页面日志放到主流中
 *  将不同流的数据写回到kafka的dwd主题中
 */
public class BaseLogApp {
    public static void main(String[] args) throws Exception {
        //TODO 1.基本环境准备
        //1.1 流处理环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        //1.2 设置并行度
        env.setParallelism(4);
        //TODO 2.检查点相关设置
        //2.1 开启检查点
        env.enableCheckpointing(5000L, CheckpointingMode.EXACTLY_ONCE);
        //2.2 设置检查点超时时间
        env.getCheckpointConfig().setCheckpointTimeout(60000);
        //2.3 设置重启策略
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3,3000L));
        //2.4 设置job取消后，检查点是否保留
        env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        //2.5 设置状态后端   内存|文件系统|RocksDB
        env.setStateBackend(new FsStateBackend("hdfs://hadoop102:8020/ck/gmall"));
        //2.6 指定操作HDFS的用户
        System.setProperty("HADOOP_USER_NAME","atguigu");

        //TODO 3.从Kafka中读取数据
        //3.1 声明消费的主题以及消费者组
        String topic = "ods_base_log";
        String groupId = "base_log_app_group";
        //3.2 获取kafka消费者
        FlinkKafkaConsumer<String> kafkaSource = MyKafkaUtil.getKafkaSource(topic,groupId);
        //3.3 读取数据  封装为流
        DataStreamSource<String> kafkaDS = env.addSource(kafkaSource);

        //TODO 4.对读取的数据进行结构的转换   jsonStr->jsonObj
        //匿名内部类方式实现
        /*SingleOutputStreamOperator<JSONObject> jsonObjDS = kafkaDS.map(
            new MapFunction<String, JSONObject>() {
                @Override
                public JSONObject map(String jsonStr) throws Exception {
                    return JSON.parseObject(jsonStr);
                }
            }
        );
        //lambda表达式实现
        SingleOutputStreamOperator<JSONObject> jsonObjDS = kafkaDS.map(
            jsonStr -> JSON.parseObject(jsonStr)
        );
        */
        // 方法的默认调用
        SingleOutputStreamOperator<JSONObject> jsonObjDS = kafkaDS.map(JSON::parseObject);

        jsonObjDS.print(">>>");


        //TODO 5.新老访客状态进行修复
        //5.1 按照设备id分组
        KeyedStream<JSONObject, String> keyedDS = jsonObjDS.keyBy(
                jsonObj -> jsonObj.getJSONObject("common").getString("mid")
        );

        // 5.2 修复
        SingleOutputStreamOperator<JSONObject>  midWithNewFlagDS = keyedDS.map(
                new RichMapFunction<JSONObject, JSONObject>() {
                    private ValueState<String> lastVisitDateState;
                    private SimpleDateFormat sdf;

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        super.open(parameters);
                        lastVisitDateState = getRuntimeContext().getState(
                                new ValueStateDescriptor<String>("newMidDateState", String.class)
                        );
                        sdf = new SimpleDateFormat("yyyyMMdd");
                    }


                    @Override
                    public JSONObject map(JSONObject jsonObj) throws Exception {
                        //获取新老访客状态
                        String isNew = jsonObj.getJSONObject("common").getString("is_new");
                        if ("1".equals(isNew)) {
                            //如果是信访科状态,才有可能需要进行修复; 如果老访客则不用修复
                            String lastVisitDate = lastVisitDateState.value();
                            String curVisitDate = sdf.format(jsonObj.getLong("ts"));
                            //判断状态中的上次访问日期是否为空
                            if (lastVisitDate != null && lastVisitDate.length() > 0) {
                                //访问过
                                //判断是否是同一天访问
                                if (!lastVisitDate.equals(curVisitDate)) {
                                    isNew = "0";
                                    jsonObj.getJSONObject("common").put("is_new", isNew);

                                }

                            } else {
                                //还没访问过 当前是第一次
                                lastVisitDateState.update(curVisitDate);
                            }

                        }
                        return jsonObj;
                    }
                }
        );

        //TODO 6.按照日志类型对日志进行分流
        

        //TODO 7.将不同流的数据写到kafka的dwd不同的主题中


        env.execute();

    }
}
