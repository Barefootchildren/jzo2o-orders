package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.core.injector.methods.SelectById;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.CustomerClient;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.common.constants.ErrorInfo.Code.TRADE_FAILED;
import static com.jzo2o.orders.base.constants.RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {

@Resource
private CustomerClient customerClient;

    @Resource
    private IOrdersCreateService owner;

    @Resource
    private RedisTemplate<String, Long> redisTemplate;
    /**
     * 生成订单id 格式：{yyMMdd}{13位id}
     *
     * @return
     */
    private Long generateOrderId() {
        //通过Redis自增序列得到序号
        Long id = redisTemplate.opsForValue().increment(ORDERS_SHARD_KEY_ID_GENERATOR, 1);
        long orderId = DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd") * 10000000000000L + id;
        return orderId;
    }
    @Override
    public PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO) {
        // 1.数据校验
        // 校验服务地址
        AddressBookResDTO detail = customerClient.getDetail(placeOrderReqDTO.getAddressBookId());
        if (detail == null) {
            throw new BadRequestException("预约地址异常，无法下单");
        }
        // 服务
        ServeAggregationResDTO serveResDTO = customerClient.getServeDetail(placeOrderReqDTO.getServeId());
        //服务下架不可下单
        if (serveResDTO == null || serveResDTO.getSaleStatus() != 2) {
            throw new BadRequestException("服务不可用");
        }


        // 2.下单前数据准备
        Orders orders = new Orders();
        // id 订单id
        orders.setId(generateOrderId());
        // userId
        orders.setUserId(UserContext.currentUserId());
        // 订单状态
        orders.setOrdersStatus(0);
        // 支付状态，暂不支持，初始化一个空状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        // 服务时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        // 购买数量
        orders.setPurNum(NumberUtils.null2Default(placeOrderReqDTO.getPurNum(), 1));
        // 地理位置
        orders.setLon(detail.getLon());
        orders.setLat(detail.getLat());

        String serveAddress = new StringBuffer(detail.getProvince())
                .append(detail.getCity())
                .append(detail.getCounty())
                .append(detail.getAddress())
                .toString();
        orders.setServeAddress(serveAddress);
        // 联系人
        orders.setContactsName(detail.getName());
        orders.setContactsPhone(detail.getPhone());

        //服务类型信息
        orders.setServeTypeId(serveResDTO.getServeTypeId());
        orders.setServeTypeName(serveResDTO.getServeTypeName());
        // 服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        // 服务项id
        orders.setServeItemId(serveResDTO.getServeItemId());
        orders.setServeItemName(serveResDTO.getServeItemName());
        orders.setServeItemImg(serveResDTO.getServeItemImg());
        orders.setUnit(serveResDTO.getUnit());
        // 价格
        orders.setPrice(serveResDTO.getPrice());
        // 城市编码
        orders.setCityCode(serveResDTO.getCityCode());
        // 计算
        // 订单总金额 价格 * 购买数量
        orders.setTotalAmount(orders.getPrice().multiply(new BigDecimal(orders.getPurNum())));
        // 优惠金额 当前默认0
        orders.setDiscountAmount(BigDecimal.ZERO);
        // 实付金额 订单总金额 - 优惠金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));
        //排序字段,根据服务开始时间转为毫秒时间戳+订单后5位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);
        //保存订单
        owner.add(orders);

        return new PlaceOrderResDTO(orders.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }
    }
    @Resource
    private TradeProperties tradeProperties;
    @Resource
    private NativePayApi nativePayApi;
        /**
     * 订单支付处理方法
     * @param id 订单ID
     * @param ordersPayReqDTO 订单支付请求数据传输对象
     * @return OrdersPayResDTO 订单支付响应数据传输对象
     */
    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        // 查询订单信息
        Orders orders = baseMapper.selectById(id);
        if(ObjectUtils.isNull( orders)){
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }

        // 检查订单是否已支付成功且交易订单号不为空
        if(OrderPayStatusEnum.PAY_SUCCESS.getStatus()==orders.getPayStatus()
                &&ObjectUtils.isNotEmpty(orders.getTradingOrderNo())){
            // 订单已支付，直接返回支付结果
            OrdersPayResDTO payResDTO = new OrdersPayResDTO();
            BeanUtil.copyProperties(orders, payResDTO);
            payResDTO.setProductOrderNo(orders.getId());
            return payResDTO;
        }else {
            // 订单未支付，生成二维码进行支付
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO.getTradingChannel());
            return BeanUtil.toBean(nativePayResDTO, OrdersPayResDTO.class);
        }
    }
    @Resource
    private TradingApi tradingApi;
    /**
     * 从交易服务器获取支付结果
     *
     * @param id 订单ID
     * @return 订单支付结果DTO对象
     */
    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        // 查询订单信息
        Orders orders = baseMapper.selectById(id);
        if(ObjectUtils.isNull( orders)){
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        Integer payStatus = orders.getPayStatus();

        // 如果订单未支付且存在交易订单号，则查询交易结果
        if(OrderPayStatusEnum.NO_PAY.getStatus()==payStatus&&
        ObjectUtils.isNotEmpty(orders.getTradingOrderNo())){
            TradingResDTO tradingResDTO = tradingApi.findTradResultByTradingOrderNo(orders.getTradingOrderNo());
            // 如果交易结果存在且状态为已结束，则处理支付成功逻辑
            if(ObjectUtils.isNotNull(tradingResDTO)&&ObjectUtils.equals(tradingResDTO.getTradingState(), TradingStateEnum.YJS)){
                TradeStatusMsg msg = TradeStatusMsg.builder()
                        .productOrderNo(orders.getId())
                        .tradingChannel(tradingResDTO.getTradingChannel())
                        .statusCode(TradingStateEnum.YJS.getCode())
                        .tradingOrderNo(tradingResDTO.getTradingOrderNo())
                        .transactionId(tradingResDTO.getTransactionId())
                        .build();
                owner.paySuccess(msg);
                OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(msg, OrdersPayResDTO.class);
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }
        }

        // 构造并返回订单支付结果
        OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
        ordersPayResDTO.setPayStatus(payStatus);
        ordersPayResDTO.setProductOrderNo(orders.getId());
        ordersPayResDTO.setTradingOrderNo(orders.getTradingOrderNo());
        ordersPayResDTO.setTradingChannel(orders.getTradingChannel());
        return ordersPayResDTO;
    }


    /**
     * 处理支付成功的业务逻辑
     * @param tradeStatusMsg 交易状态消息对象，包含支付相关的交易信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        // 查询订单信息
        Orders orders = baseMapper.selectById(tradeStatusMsg.getProductOrderNo());
        if(ObjectUtils.isNull( orders)){
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        // 检查订单支付状态，避免重复支付
        if(ObjectUtils.notEqual(OrderPayStatusEnum.NO_PAY.getStatus(),orders.getPayStatus())){
            log.info("订单已支付，请勿重复支付");
            return;
        }
        // 检查订单状态，避免重复支付
        if(ObjectUtils.notEqual(OrderStatusEnum.NO_PAY.getStatus(),orders.getOrdersStatus())){
            log.info("订单已支付，请勿重复支付");
            return;
        }
        // 验证第三方支付单号是否存在
        if(ObjectUtils.isEmpty(orders.getTradingOrderNo())){
            throw new CommonException("支付成功通知：缺少第三方支付单号");
        }
        // 更新订单支付相关信息
        boolean update = lambdaUpdate()
                .eq(Orders::getId, orders.getId())
                .set(Orders::getPayTime, LocalDateTime.now())
                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())
                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())
                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())
                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())
                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())
                .update();
        // 检查订单更新结果
        if(!update){
            log.info("支付成功通知："+orders.getId()+"更新订单失败");
            throw new CommonException("支付成功通知："+orders.getId()+"更新订单失败");
        }
    }

    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        return lambdaQuery()
                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                .lt(Orders::getCreateTime, LocalDateTime.now().minusMinutes(15))
                .last("limit " + count)
                .list();
    }


    /**
     * 生成支付二维码
     *
     * @param orders 订单信息
     * @param tradingChannel 支付渠道枚举
     * @return NativePayResDTO 二维码支付响应数据传输对象
     */
    private NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {
        // 根据支付渠道获取对应的企业ID
        Long enterpriseId = ObjectUtils.equal(PayChannelEnum.ALI_PAY, tradingChannel) ?
                tradeProperties.getAliEnterpriseId() : tradeProperties.getWechatEnterpriseId();
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();
        nativePayReqDTO.setEnterpriseId(enterpriseId);
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        nativePayReqDTO.setProductOrderNo(orders.getId());
        nativePayReqDTO.setTradingChannel(tradingChannel);
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());
        nativePayReqDTO.setMemo(orders.getServeItemName());

        // 判断是否需要切换支付渠道
        if(ObjectUtils.isNotEmpty(orders.getTradingChannel())
        &&ObjectUtils.notEqual(orders.getTradingChannel(), tradingChannel.toString())){
            nativePayReqDTO.setChangeChannel(true);
        }

        // 调用支付接口创建线下交易
        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayReqDTO);
        if (ObjectUtils.isNotNull(downLineTrading)){
            log.info("订单:{}请求支付,生成二维码:{}",orders.getId(),downLineTrading.toString());
            // 更新订单的交易单号和交易渠道
            boolean update = lambdaUpdate().eq(Orders::getId, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingOrderNo, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingChannel, downLineTrading.getTradingChannel())
                    .update();
            if(!update){
                throw new CommonException("订单"+orders.getId()+"请求支付更新交易单号失败");
            }
        }
        return downLineTrading;
    }

}