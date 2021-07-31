package com.atguigu.gmall.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Author: Ao Tang
 * Desc:  熟悉SpringBoot处理流程
 */
//标识为controller组件，交给Sprint容器管理，并接收处理请求  如果返回String，会当作网页进行跳转
//@Controller
//RestController = @Controller + @ResponseBody  会将返回结果转换为json进行响应
@RestController
@Slf4j
public class LoggerController {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @RequestMapping("/applog")
    public String getLogger(@RequestParam("param") String jsonStr) {
        //将数据落盘
        log.info(jsonStr);
        //将数据发送至Kafka ODS主题
        kafkaTemplate.send("ods_base_log", jsonStr);
        return "success";
    }
}

