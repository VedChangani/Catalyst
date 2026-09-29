package in.vedchangani.billingsoftware.service;

import com.razorpay.RazorpayException;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;

public interface RazorpayService {

    RazorpayOrderResponse createOrder(String localOrderId, String currency) throws RazorpayException;

    boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);
}
