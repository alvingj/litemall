package org.linlinjava.litemall.wx.web;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.linlinjava.litemall.core.util.JacksonUtil;
import org.linlinjava.litemall.core.util.ResponseUtil;
import org.linlinjava.litemall.core.validator.Order;
import org.linlinjava.litemall.core.validator.Sort;
import org.linlinjava.litemall.db.domain.LitemallCart;
import org.linlinjava.litemall.db.domain.LitemallCoupon;
import org.linlinjava.litemall.db.domain.LitemallCouponUser;
import org.linlinjava.litemall.db.domain.LitemallGrouponRules;
import org.linlinjava.litemall.db.service.*;
import org.linlinjava.litemall.db.util.CouponConstant;
import org.linlinjava.litemall.wx.annotation.LoginUser;
import org.linlinjava.litemall.wx.dao.CouponVo;
import org.linlinjava.litemall.wx.util.WxResponseCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券服务
 */
@RestController
@RequestMapping("/wx/coupon")
@Validated
public class WxCouponController {
    private final Log logger = LogFactory.getLog(WxCouponController.class);

    @Autowired
    private LitemallCouponService couponService;
    @Autowired
    private LitemallCouponUserService couponUserService;
    @Autowired
    private LitemallGrouponRulesService grouponRulesService;
    @Autowired
    private LitemallCartService cartService;
    @Autowired
    private CouponVerifyService couponVerifyService;

    /**
     * 优惠券列表
     *
     * @param page
     * @param size
     * @param sort
     * @param order
     * @return
     */
    @GetMapping("list")
    public Object list(@RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(defaultValue = "10") Integer size,
                       @Sort @RequestParam(defaultValue = "add_time") String sort,
                       @Order @RequestParam(defaultValue = "desc") String order) {

        List<LitemallCoupon> couponList = couponService.queryList(page, size, sort, order);
        int total = couponService.queryTotal();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("data", couponList);
        data.put("count", total);
        return ResponseUtil.ok(data);
    }

    /**
     * 个人优惠券列表
     *
     * @param userId
     * @param status
     * @param page
     * @param size
     * @param sort
     * @param order
     * @return
     */
    @GetMapping("mylist")
    public Object mylist(@LoginUser Integer userId,
                       @NotNull Short status,
                       @RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(defaultValue = "10") Integer size,
                       @Sort @RequestParam(defaultValue = "add_time") String sort,
                       @Order @RequestParam(defaultValue = "desc") String order) {
        if (userId == null) {
            return ResponseUtil.unlogin();
        }

        List<LitemallCouponUser> couponUserList = couponUserService.queryList(userId, null, status, page, size, sort, order);
        List<CouponVo> couponVoList = change(couponUserList);
        int total = couponService.queryTotal();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("data", couponVoList);
        data.put("count", total);
        return ResponseUtil.ok(data);
    }

    private List<CouponVo> change(List<LitemallCouponUser> couponList) {
        List<CouponVo> couponVoList = new ArrayList<>(couponList.size());
        for(LitemallCouponUser couponUser : couponList){
            Integer couponId = couponUser.getCouponId();
            LitemallCoupon coupon = couponService.findById(couponId);
            CouponVo couponVo = new CouponVo();
            couponVo.setId(coupon.getId());
            couponVo.setName(coupon.getName());
            couponVo.setDesc(coupon.getDesc());
            couponVo.setTag(coupon.getTag());
            couponVo.setMin(coupon.getMin().toPlainString());
            couponVo.setDiscount(coupon.getDiscount().toPlainString());

            Short days = coupon.getDays();
            if (days == 0) {
                couponVo.setStartTime(coupon.getStartTime());
                couponVo.setEndTime(coupon.getEndTime());
            }
            else{
                couponVo.setStartTime(coupon.getAddTime());
                couponVo.setEndTime(coupon.getAddTime().plusDays(days));
            }
            couponVoList.add(couponVo);
        }

        return couponVoList;
    }


    /**
     * 当前购物车下单商品订单可用优惠券
     *
     * @param userId
     * @param cartId
     * @param grouponRulesId
     * @return
     */
    @GetMapping("selectlist")
    public Object selectlist(@LoginUser Integer userId, Integer cartId, Integer grouponRulesId) {
        if (userId == null) {
            return ResponseUtil.unlogin();
        }

        // 团购优惠
        BigDecimal grouponPrice = new BigDecimal(0.00);
        LitemallGrouponRules grouponRules = grouponRulesService.queryById(grouponRulesId);
        if (grouponRules != null) {
            grouponPrice = grouponRules.getDiscount();
        }

        // 商品价格
        List<LitemallCart> checkedGoodsList = null;
        if (cartId == null || cartId.equals(0)) {
            checkedGoodsList = cartService.queryByUidAndChecked(userId);
        } else {
            LitemallCart cart = cartService.findById(cartId);
            if (cart == null) {
                return ResponseUtil.badArgumentValue();
            }
            checkedGoodsList = new ArrayList<>(1);
            checkedGoodsList.add(cart);
        }
        BigDecimal checkedGoodsPrice = new BigDecimal(0.00);
        for (LitemallCart cart : checkedGoodsList) {
            //  只有当团购规格商品ID符合才进行团购优惠
            if (grouponRules != null && grouponRules.getGoodsId().equals(cart.getGoodsId())) {
                checkedGoodsPrice = checkedGoodsPrice.add(cart.getPrice().subtract(grouponPrice).multiply(new BigDecimal(cart.getNumber())));
            } else {
                checkedGoodsPrice = checkedGoodsPrice.add(cart.getPrice().multiply(new BigDecimal(cart.getNumber())));
            }
        }

        // 计算优惠券可用情况
        List<LitemallCouponUser> couponUserList = couponUserService.queryAll(userId);
        List<LitemallCouponUser> availableCouponUserList = new ArrayList<>(couponUserList.size());
        for (LitemallCouponUser couponUser : couponUserList) {
            LitemallCoupon coupon = couponVerifyService.checkCoupon(userId, couponUser.getCouponId(), checkedGoodsPrice);
            if (coupon == null) {
                continue;
            }
            availableCouponUserList.add(couponUser);
        }

        List<CouponVo> couponVoList = change(availableCouponUserList);

        return ResponseUtil.ok(couponVoList);
    }

    /**
     * 优惠券领取
     *
     * @param userId 用户ID
     * @param body 请求内容， { couponId: xxx }
     * @return 操作结果
     */
    @PostMapping("receive")
    public Object receive(@LoginUser Integer userId, @RequestBody String body) {
        if (userId == null) {
            return ResponseUtil.unlogin();
        }

        Integer couponId = JacksonUtil.parseInteger(body, "couponId");
        if(couponId == null){
            return ResponseUtil.badArgument();
        }

        LitemallCoupon coupon = couponService.findById(couponId);
        if(coupon == null){
            return ResponseUtil.badArgumentValue();
        }

        // 当前已领取数量和总数量比较
        Integer total = coupon.getTotal();
        Integer totalCoupons = couponUserService.countCoupon(couponId);
        if((total != 0) && (totalCoupons >= total)){
            return ResponseUtil.fail(WxResponseCode.COUPON_EXCEED_LIMIT, "优惠券已领完");
        }

        // 当前用户已领取数量和用户限领数量比较
        Integer limit = coupon.getLimit().intValue();
        Integer userCounpons = couponUserService.countUserAndCoupon(userId, couponId);
        if((limit != 0) && (userCounpons >= limit)){
            return ResponseUtil.fail(WxResponseCode.COUPON_EXCEED_LIMIT, "优惠券已经领取过");
        }

        // 优惠券分发类型
        // 例如注册赠券类型的优惠券不能领取
        Short type = coupon.getType();
        if(type.equals(CouponConstant.TYPE_REGISTER)){
            return ResponseUtil.fail(WxResponseCode.COUPON_RECEIVE_FAIL, "新用户优惠券自动发送");
        }
        else if(!type.equals(CouponConstant.TYPE_COMMON)){
            return ResponseUtil.fail(WxResponseCode.COUPON_RECEIVE_FAIL, "优惠券类型不支持");
        }

        // 优惠券状态，已下架或者过期不能领取
        Short status = coupon.getStatus();
        if(status.equals(CouponConstant.STATUS_OUT)){
            return ResponseUtil.fail(WxResponseCode.COUPON_EXCEED_LIMIT, "优惠券已领完");
        }
        if(status.equals(CouponConstant.STATUS_EXPIRED)){
            return ResponseUtil.fail(WxResponseCode.COUPON_RECEIVE_FAIL, "优惠券已经过期");
        }

        // 用户领券记录
        LitemallCouponUser couponUser = new LitemallCouponUser();
        couponUser.setCouponId(couponId);
        couponUser.setUserId(userId);

        couponUserService.add(couponUser);

        return ResponseUtil.ok();
    }
}