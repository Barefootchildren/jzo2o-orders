package com.jzo2o.orders.manager.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author Mr.M
 * @version 1.0
 * @description 调用customer的客户端类
 * @date 2023/12/3 21:27
 */
@Component
@Slf4j
public class CustomerClient {

    @Resource
    private AddressBookApi addressBookApi;
    @Resource
    private ServeApi serveApi;

    @SentinelResource(value = "getAddressBookDetail", fallback = "detailFallback", blockHandler = "detailBlockHandler")
    public AddressBookResDTO getDetail(Long id) {
        log.error("根据id查询地址簿，id:{}", id);
        // 调用其他微服务方法
        return addressBookApi.detail(id);
    }
    @SentinelResource(value = "getServeDetail",fallback = "detailFallback",blockHandler = "detailBlockHandler")
    public ServeAggregationResDTO getServeDetail(Long id) {
        log.error("根据id查询服务聚合信息，id:{}", id);
        // 调用其他微服务方法
        return serveApi.findById(id);
    }

    //执行异常走
    public AddressBookResDTO detailFallback(Long id, Throwable throwable) {
        log.error("非限流、熔断等导致的异常执行的降级方法，id:{},throwable:", id, throwable);
        return null;
    }

    //熔断后的降级逻辑
    public AddressBookResDTO detailBlockHandler(Long id, BlockException blockException) {
        log.error("触发限流、熔断时执行的降级方法，id:{},blockException:", id, blockException);
        return null;
    }
}