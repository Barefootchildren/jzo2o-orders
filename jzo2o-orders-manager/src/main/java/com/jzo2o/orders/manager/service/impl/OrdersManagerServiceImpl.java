package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.db.DbRuntimeException;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.handler.OrdersHandler;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.redis.helper.CacheHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;
import static com.jzo2o.orders.base.constants.RedisConstants.RedisKey.ORDERS;
import static com.jzo2o.orders.base.constants.RedisConstants.Ttl.ORDERS_PAGE_TTL;

/**
 * <p>
 * 订单表 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids).ge(Orders::getUserId, 0);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }
    @Resource
    private CacheHelper cacheHelper;

/**
 * 消费者查询订单列表
 *
 * @param currentUserId 当前用户ID，用于筛选该用户下的订单
 * @param ordersStatus  订单状态，可为空，为空时不作为筛选条件
 * @param sortBy        排序字段值（通常为时间戳等），用于分页查询，小于该值的数据会被查询出来
 * @return 返回订单简单信息的列表，每个元素为 {@link OrderSimpleResDTO}
 */
@Override
public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
    // 1. 构建查询条件：根据订单状态、排序字段和用户ID进行筛选，并只查询启用状态的订单ID
    LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
            .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
            .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
            .eq(Orders::getUserId, currentUserId)
            .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
            .select(Orders::getId);
    Page<Orders> queryPage = new Page<>();
    queryPage.addOrder(OrderItem.desc(SORT_BY));
    queryPage.setSearchCount(false);
    // 2. 执行分页查询获取订单ID列表
    String key = String.format(ORDERS, currentUserId);
    Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
    // 如果没有查询到订单记录，则直接返回空列表
    if(ObjectUtils.isEmpty(ordersPage.getRecords())){
        return new ArrayList<>();
    }
    // 3. 提取订单ID集合，用于后续批量查询或缓存获取
    List<Long> orderIds = CollUtils.getFieldValues(ordersPage.getRecords(), Orders::getId);
    // 4. 使用缓存辅助工具批量获取订单数据，未命中缓存的部分通过 batchQuery 查询并转换为 DTO 对象
    return cacheHelper.batchGet(key, orderIds, (noCacheIds, clazz) -> {
        List<Orders> ordersList = batchQuery(noCacheIds);
        if (CollUtils.isEmpty(ordersList)) {
            return new HashMap<>();
        }
        return ordersList.stream().collect(Collectors.toMap(Orders::getId, o -> BeanUtils.toBean(o, OrderSimpleResDTO.class)));
    }, OrderSimpleResDTO.class, ORDERS_PAGE_TTL);
}

    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
        //Orders orders = queryById(id);
        String currentSnapshotCache = orderStateMachine.getCurrentSnapshotCache(String.valueOf(id));
        OrderSnapshotDTO snapshotDTO = JSONUtil.toBean(currentSnapshotCache, OrderSnapshotDTO.class);
        snapshotDTO = canalIfPayOvertime(snapshotDTO);
        return BeanUtil.toBean(snapshotDTO, OrderResDTO.class);
    }

    @Resource
    private IOrdersCreateService ordersCreateService;
        /**
     * 处理支付超时的订单
     * <p>
     * 检查订单是否处于未支付状态且创建时间已超过15分钟，如果是则从交易服务器获取最新的支付结果，
     * 如果支付未成功则自动取消该订单。
     *
     * @param snapshotDTO 订单快照数据传输对象，包含订单的当前状态和创建时间等信息
     * @return 处理后的订单快照数据传输对象，如果订单被取消则返回更新后的快照，否则返回原快照
     */
    private OrderSnapshotDTO canalIfPayOvertime(OrderSnapshotDTO snapshotDTO) {
        Integer payStatus = snapshotDTO.getPayStatus();
        // 检查订单是否为未支付状态且创建时间超过15分钟
        if (Objects.equals(payStatus, OrderStatusEnum.NO_PAY.getStatus())
                && snapshotDTO.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())) {
            // 从交易服务器获取支付结果
            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradServer(snapshotDTO.getId());
            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
            // 如果支付状态不是支付成功，则取消订单
            if (payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()) {
                OrderCancelDTO orderCancelDTO = BeanUtils.toBean(snapshotDTO, OrderCancelDTO.class);
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("订单支付超时自动取消");
                cancel(orderCancelDTO);
                //orders = queryById(orders.getId());
                String currentSnapshotCache = orderStateMachine.getCurrentSnapshotCache(String.valueOf(snapshotDTO.getId()));
                 snapshotDTO = JSONUtil.toBean(currentSnapshotCache, OrderSnapshotDTO.class);
                 return snapshotDTO;
            }
        }
        return snapshotDTO;
    }

    /**
     * 检查订单是否支付超时，如果超时则取消订单
     *
     * @param orders 待检查的订单对象
     * @return 处理后的订单对象，如果订单被取消则返回更新后的订单状态
     */
/*    private Orders canalIfPayOvertime(Orders orders) {
        // 检查订单是否为未支付状态且创建时间超过15分钟
        if (Objects.equals(orders.getOrdersStatus(), OrderStatusEnum.NO_PAY.getStatus())
                && orders.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())) {
            // 从交易服务器获取支付结果
            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradServer(orders.getId());
            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
            // 如果支付状态不是支付成功，则取消订单
            if (payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()) {
                OrderCancelDTO orderCancelDTO = BeanUtils.toBean(orders, OrderCancelDTO.class);
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("订单支付超时自动取消");
                cancel(orderCancelDTO);
                orders = queryById(orders.getId());
            }
        }
        return orders;
    }*/


    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    @Resource
    private OrdersManagerServiceImpl owner;
    @Resource
    private OrdersHandler ordersHandler;
    /**
     * 取消订单
     *
     * @param orderCancelDTO 订单取消信息传输对象，包含要取消的订单ID等信息
     */
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        // 根据订单ID查询订单信息
        Orders orders = getById(orderCancelDTO.getId());
        if (ObjectUtils.isNull(orders)) {
            throw new DbRuntimeException("找不到要取消的订单，订单号：{}", orderCancelDTO.getId());
        }

        // 获取订单当前状态
        Integer ordersStatus = orders.getOrdersStatus();

        // 根据不同订单状态执行相应的取消逻辑
        if (Objects.equals(OrderStatusEnum.NO_PAY.getStatus(), ordersStatus)) {
            // 未支付状态下的订单取消处理
            owner.cancelByNoPay(orderCancelDTO);
        } else if (Objects.equals(OrderStatusEnum.DISPATCHING.getStatus(), ordersStatus)) {
            // 配送中状态下的订单取消处理
            owner.cancelByDispatching(orderCancelDTO);
            ordersHandler.requestRefundNewThread(orders.getId());
        } else {
            throw new CommonException("当前订单状态不支持取消");
        }
    }
    @Resource
    private IOrdersRefundService ordersRefundService;
    /**
     * 取消配送中的订单
     *
     * @param orderCancelDTO 订单取消信息传输对象，包含订单ID、当前用户信息、交易订单号、实际支付金额等
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO) {
        //保存取消订单记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

        //更新订单状态为已取消，并设置退款状态为退款中
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .payTime(LocalDateTime.now())
                .tradingOrderNo(orderCancelDTO.getId())
                .thirdOrderId(String.valueOf(orderCancelDTO.getTradingOrderNo()))
                .build();
        orderStateMachine.changeStatus(orderCancelDTO.getUserId(),String.valueOf(orderCancelDTO.getId()), OrderStatusChangeEventEnum.CANCEL,orderSnapshotDTO);
       /* OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.DISPATCHING.getStatus())
                .targetStatus(OrderStatusEnum.CANCELED.getStatus())
                .refundStatus(OrderRefundStatusEnum.REFUNDING.getStatus())
                .build();
        Integer result = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (result <= 0) {
            throw new CommonException("待服务订单关闭事件处理失败");
        }*/

        //保存订单退款记录
        OrdersRefund ordersRefund = new OrdersRefund();
        ordersRefund.setId(orderCancelDTO.getId());
        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
        ordersRefundService.save(ordersRefund);
    }



    @Resource
    private IOrdersCanceledService ordersCanceledService;
    @Resource
    private IOrdersCommonService ordersCommonService;
    @Resource
    private OrderStateMachine orderStateMachine;
    /**
     * 取消未支付订单
     *
     * @param orderCancelDTO 订单取消信息传输对象，包含订单ID、当前用户ID、用户名、用户类型等信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByNoPay(OrderCancelDTO orderCancelDTO) {
        // 构建订单取消记录并保存
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

        // 更新订单状态从未支付到已取消
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .payTime(LocalDateTime.now())
                .tradingOrderNo(orderCancelDTO.getId())
                .thirdOrderId(String.valueOf(orderCancelDTO.getTradingOrderNo()))
                .build();
        orderStateMachine.changeStatus(orderCancelDTO.getUserId(),String.valueOf(orderCancelDTO.getId()), OrderStatusChangeEventEnum.CANCEL,orderSnapshotDTO);
/*        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.NO_PAY.getStatus())
                .targetStatus(OrderStatusEnum.CANCELED.getStatus())
                .build();
        Integer result = ordersCommonService.updateStatus(orderUpdateStatusDTO);

        // 检查订单状态更新结果，失败则抛出异常
        if (result <= 0) {
            throw new CommonException("订单取消事件处理失败");
        }*/
    }

}
