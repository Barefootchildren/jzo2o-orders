package com.jzo2o.orders.manager.service.impl;

import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.service.CustomerClient;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}