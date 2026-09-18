package in.vedchangani.billingsoftware.service;

import com.razorpay.RazorpayException;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;

public interface RazorpayService {

    // The amount is never a parameter here: it is resolved server-side from the local
    // order's authoritative grandTotal, keyed by localOrderId. See RazorpayServiceImpl.
    RazorpayOrderResponse createOrder(String localOrderId, String currency) throws RazorpayException;

    /**
     * Verifies the HMAC-SHA256 signature Razorpay returns with a completed payment, using the
     * server-side key secret. The secret lives only in the service layer and is never passed
     * in, returned, or logged.
     *
     * @return true only if the signature genuinely matches the order/payment pair.
     */
    boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);
}
