package com.jzo2o.orders.manager.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.api.trade.enums.RefundStatusEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
@Slf4j
@Component
public class OrdersHandler {
    @Resource
    private IOrdersCreateService ordersCreateService;
    @Resource
    private IOrdersManagerService ordersManagerService;
    @Resource
    private RefundRecordApi refundRecordApi;
    //解决同级方法调用，事务失效问题
    @Resource
    private OrdersHandler ordersHandler;
    @Resource
    private IOrdersRefundService ordersRefundService;
    @Resource
    private OrdersMapper ordersMapper;

    /**
     * 支付超时取消订单
     * 每分钟执行一次
     */
    @XxlJob(value = "cancelOverTimePayOrder")
    public void cancelOverTimePayOrder() {
        // 查询超时未支付的订单列表，每次处理最多100条记录
        List<Orders> ordersList = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        if (CollUtil.isEmpty(ordersList)) {
            XxlJobHelper.log("无超时订单");
            return;
        }
        // 遍历超时订单列表，逐个取消订单
        for (Orders orders : ordersList) {
            // 将订单对象转换为取消订单DTO，并设置系统取消相关参数
            OrderCancelDTO orderCancelDTO = BeanUtil.toBean(orders, OrderCancelDTO.class);
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
            orderCancelDTO.setCancelReason("支付超时取消订单");
            // 执行订单取消操作
            ordersManagerService.cancel(orderCancelDTO);
        }
    }
    /**
     * 处理退款订单任务
     * 该方法通过XXL-JOB定时调度，批量处理待退款的订单
     */
    @XxlJob(value = "handleRefundOrders")
    public void handleRefundOrders() {
        // 查询待处理的退款订单列表，最多获取100条记录
        List<OrdersRefund> ordersRefundList = ordersRefundService.queryRefundOrderListByCount(100);

        // 如果没有待处理的退款订单，则记录日志并退出
        if (CollUtil.isEmpty(ordersRefundList)) {
            XxlJobHelper.log("无退款订单");
            return;
        }

        // 遍历处理每个退款订单
        for (OrdersRefund ordersRefund : ordersRefundList) {
            requestRefundOrder(ordersRefund);
        }
    }


    /**
     * 请求退款订单处理
     * @param ordersRefund 订单退款信息对象，包含交易订单号和实际支付金额等信息
     */
    private void requestRefundOrder(OrdersRefund ordersRefund) {
        ExecutionResultResDTO executionResultResDTO=null;
        try {
            // 调用退款接口执行退款操作
            executionResultResDTO=refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getRealPayAmount());
        }catch (Exception e){
            e.printStackTrace();
        }
        // 如果退款执行结果不为空，则处理退款订单状态更新
        if(executionResultResDTO!=null){
            ordersHandler.refundOrder(ordersRefund,executionResultResDTO);
        }
    }


    /**
     * 处理订单退款结果更新
     * 根据退款执行结果更新订单的退款状态，包括退款成功、退款失败或退款中状态
     * @param ordersRefund 订单退款信息对象，包含需要更新的订单ID等信息
     * @param executionResultResDTO 退款执行结果DTO，包含退款状态、退款ID、退款编号等结果信息
     */
    private void refundOrder(OrdersRefund ordersRefund, ExecutionResultResDTO executionResultResDTO) {
        // 根据退款执行结果确定最终的退款状态
        int refundingStatus = OrderRefundStatusEnum.REFUNDING.getStatus();
        if(ObjectUtils.equals(RefundStatusEnum.SUCCESS.getCode(),executionResultResDTO.getRefundStatus())){
            refundingStatus= OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        } else if (ObjectUtils.equals(RefundStatusEnum.FAIL.getCode(),executionResultResDTO.getRefundStatus())) {
            refundingStatus=OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }

        // 如果退款状态仍为退款中，则不进行后续处理
        if(ObjectUtils.equals(refundingStatus, OrderRefundStatusEnum.REFUNDING)){
            return;
        }

        // 构建订单更新条件，只更新退款状态发生变化的订单
        LambdaUpdateWrapper<Orders> updateWrapper = new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, ordersRefund.getId())
                .ne(Orders::getRefundStatus, refundingStatus)
                .set(Orders::getRefundStatus, refundingStatus)
                .set(ObjectUtils.isNotEmpty(executionResultResDTO.getRefundId()), Orders::getRefundId, executionResultResDTO.getRefundId())
                .set(ObjectUtils.isNotEmpty(executionResultResDTO.getRefundNo()), Orders::getRefundNo, executionResultResDTO.getRefundNo());

        // 执行订单更新操作，如果更新成功则删除对应的订单退款记录
        int update = ordersMapper.update(null, updateWrapper);
        if(update>0){
            ordersRefundService.removeById(ordersRefund.getId());
        }
    }
    public void requestRefundNewThread(Long ordersRefundId){
        new Thread(() -> {
            OrdersRefund ordersRefund = ordersRefundService.getById(ordersRefundId);
            requestRefundOrder(ordersRefund);
        }).start();
    }
}