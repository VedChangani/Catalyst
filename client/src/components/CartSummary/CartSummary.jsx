import {useContext, useRef, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import ReceiptPopup from "../ReceiptPopup/ReceiptPopup.jsx";
import {createOrder, cancelOrder, failPaymentOrder} from "../../Service/OrderService.js";
import toast from "react-hot-toast";
import {createRazorpayOrder, verifyPayment} from "../../Service/PaymentService.js";
import {AppConstants} from "../../util/constants.js";
import Button from "../../ui/Button.jsx";

const CartSummary = ({customerName, mobileNumber, setMobileNumber, setCustomerName}) => {
    const {cartItems, clearCart} = useContext(AppContext);

    const [isProcessing, setIsProcessing] = useState(false);
    const [orderDetails, setOrderDetails] = useState(null);
    const [showPopup, setShowPopup] = useState(false);

    // Razorpay can fire more than one terminal event for a single checkout: the success handler
    // is followed by modal.ondismiss when the popup closes, and a payment.failed is likewise
    // followed by ondismiss. This ref marks a checkout attempt as already settled so only the
    // FIRST terminal event acts - without it a successful payment would immediately be followed
    // by a cancel call against the order that was just paid.
    const checkoutSettledRef = useRef(false);

    const totalAmount = cartItems.reduce((total, item) => total + item.price * item.quantity, 0);
    const tax = totalAmount * 0.01;
    const grandTotal = totalAmount + tax;

    const clearAll = () => {
        setCustomerName("");
        setMobileNumber("");
        clearCart();
    }

    const placeOrder = () => {
        setShowPopup(true);
        clearAll();
    }

    const handlePrintReceipt = () => {
        window.print();
    }

    const loadRazorpayScript = () => {
        return new Promise((resolve) => {
            const script = document.createElement('script');
            script.src = "https://checkout.razorpay.com/v1/checkout.js";
            script.onload = () => resolve(true);
            script.onerror = () => resolve(false);
            document.body.appendChild(script);
        })
    }

    const handleOrderCancellation = async (orderId) => {
        try {
            await cancelOrder(orderId);
        } catch (error) {
            console.error("Failed to cancel order:", error);
        }
    }

    const handlePaymentFailure = async (orderId) => {
        try {
            await failPaymentOrder(orderId);
        } catch (error) {
            console.error("Failed to mark payment as failed:", error);
        }
    }

    const completePayment = async (paymentMode) => {
        if (!customerName || !mobileNumber) {
            toast.error("Please enter customer details");
            return;
        }

        if (cartItems.length === 0) {
            toast.error("Your cart is empty");
            return;
        }
        // Only itemId + quantity are sent for each cart line; name, price, subtotal, tax and
        // grandTotal are never client-authoritative - the server looks up prices from the item
        // catalog and computes the totals itself. totalAmount/tax/grandTotal above remain purely
        // for the on-screen summary.
        const orderData = {
            customerName,
            phoneNumber: mobileNumber,
            cartItems: cartItems.map(({itemId, quantity}) => ({itemId, quantity})),
            paymentMethod: paymentMode.toUpperCase()
        }
        setIsProcessing(true);
        // Drop any previously verified order before starting a new attempt. Without this, a
        // successful earlier order would leave orderDetails populated and PAID, which would keep
        // "Place Order" enabled and let a receipt be shown for the WRONG order if this new
        // attempt is cancelled or fails verification.
        setOrderDetails(null);
        try {

            const response = await createOrder(orderData);
            const savedData = response.data;
            if (response.status === 201 && paymentMode === "cash") {
                toast.success("Cash received");
                setOrderDetails(savedData);
                setIsProcessing(false);
            } else if (response.status === 201 && paymentMode === "upi") {
                const razorpayLoaded = await loadRazorpayScript();
                if (!razorpayLoaded) {
                    toast.error('Unable to load razorpay');
                    await handleOrderCancellation(savedData.orderId);
                    setIsProcessing(false);
                    return;
                }

                //create razorpay order — amount is resolved server-side from the local order's
                //grandTotal (see RazorpayServiceImpl), so only the local orderId is sent here.
                const razorpayResponse = await createRazorpayOrder({currency: 'INR', orderId: savedData.orderId});

                // New checkout attempt: nothing has settled it yet.
                checkoutSettledRef.current = false;

                const options = {
                    key: AppConstants.RAZORPAY_KEY_ID,
                    amount: razorpayResponse.data.amount,
                    currency: razorpayResponse.data.currency,
                    order_id: razorpayResponse.data.id,
                    name: "My Retail Shop",
                    description: "Order payment",
                    handler: async function (response) {
                        // Razorpay's callback alone is NOT proof of payment - it only means the
                        // checkout closed. The order is settled solely by the backend's
                        // signature verification below.
                        if (checkoutSettledRef.current) return;
                        checkoutSettledRef.current = true;
                        try {
                            await verifyPaymentHandler(response, savedData);
                        } finally {
                            setIsProcessing(false);
                        }
                    },
                    prefill: {
                        name: customerName,
                        contact: mobileNumber
                    },
                    theme: {
                        color: "#2563EB"
                    },
                    modal: {
                        ondismiss: async () => {
                            // Fires on every close, including after a successful payment or a
                            // payment.failed - only act if nothing has settled this attempt yet.
                            if (checkoutSettledRef.current) return;
                            checkoutSettledRef.current = true;
                            await handleOrderCancellation(savedData.orderId);
                            toast.error("Payment cancelled");
                            setIsProcessing(false);
                        }
                    },
                };
                const rzp = new window.Razorpay(options);
                rzp.on("payment.failed", async (response) => {
                    if (checkoutSettledRef.current) return;
                    checkoutSettledRef.current = true;
                    await handlePaymentFailure(savedData.orderId);
                    toast.error("Payment failed");
                    console.error(response.error.description);
                    setIsProcessing(false);
                });
                rzp.open();
                // Deliberately NOT clearing isProcessing here. The checkout modal is now open and
                // the buttons must stay disabled until a terminal event above resolves it -
                // otherwise a second click would create a duplicate local order.
            }
        }catch(error) {
            console.error(error);
            toast.error("Payment processing failed");
            setIsProcessing(false);
        }
    }

    const verifyPaymentHandler = async (response, savedOrder) => {
        // The local orderId is what ties this payment to our own record; the backend re-checks
        // that razorpayOrderId matches the one it stored against that order.
        const paymentData = {
            razorpayOrderId: response.razorpay_order_id,
            razorpayPaymentId: response.razorpay_payment_id,
            razorpaySignature: response.razorpay_signature,
            orderId: savedOrder.orderId
        };
        try {
            const paymentResponse = await verifyPayment(paymentData);
            const verifiedOrder = paymentResponse.data;

            // Success requires BOTH a 2xx and a backend-reported PAID status. The status is the
            // server's own verdict after signature verification - we never infer PAID from the
            // Razorpay callback or from the HTTP status alone.
            if (paymentResponse.status === 200 && verifiedOrder?.orderStatus === "PAID") {
                toast.success("Payment successful");
                setOrderDetails(verifiedOrder);
            } else {
                // Verification did not confirm payment. Leave orderDetails null so the receipt
                // stays unavailable; the order keeps whatever state the backend decided.
                setOrderDetails(null);
                toast.error("Payment could not be verified. Your order has not been marked paid.");
            }
        } catch (error) {
            console.error(error);
            setOrderDetails(null);
            // A rejected verification (bad signature, mismatched Razorpay order, wrong owner,
            // invalid state) or a network failure both land here. In every case the backend
            // remains the source of truth - we do not mark anything paid client-side.
            const message = error?.response?.data?.message;
            toast.error(message
                ? `Payment verification failed: ${message}`
                : "Payment verification failed. Your order has not been marked paid.");
        }
    };

    // Receipt is only available for PAID orders
    const canPlaceOrder = orderDetails && orderDetails.orderStatus === "PAID";

    return (
        <div>
            <div className="space-y-2 border-b-2 border-ink pb-4">
                <div className="flex justify-between text-sm font-bold">
                    <span>Item: </span>
                    <span>₹{totalAmount.toFixed(2)}</span>
                </div>
                <div className="flex justify-between text-sm font-bold">
                    <span>Tax (1%):</span>
                    <span>₹{tax.toFixed(2)}</span>
                </div>
                <div className="flex items-end justify-between pt-1">
                    <span className="text-xs font-extrabold uppercase tracking-[0.16em]">Total</span>
                    <span className="text-3xl font-extrabold leading-none">₹{grandTotal.toFixed(2)}</span>
                </div>
            </div>

            <div className="mt-4 grid grid-cols-2 gap-3">
                <Button
                    variant="success"
                    className="w-full"
                    onClick={() => completePayment("cash")}
                    disabled={isProcessing}
                >
                    {isProcessing ? "Processing...": "Cash"}
                </Button>
                <Button
                    variant="primary"
                    className="w-full"
                    onClick={() => completePayment("upi")}
                    disabled={isProcessing}
                >
                    {isProcessing ? "Processing...": "UPI"}
                </Button>
            </div>
            <div className="mt-3">
                <Button
                    variant="dark"
                    className="w-full"
                    onClick={placeOrder}
                    disabled={isProcessing || !canPlaceOrder}
                >
                    Place Order
                </Button>
            </div>
            {
                showPopup && orderDetails && (
                    <ReceiptPopup
                        orderDetails={{
                            ...orderDetails,
                            razorpayOrderId: orderDetails.paymentDetails?.razorpayOrderId,
                            razorpayPaymentId: orderDetails.paymentDetails?.razorpayPaymentId,
                        }}
                        onClose={() => setShowPopup(false)}
                        onPrint={handlePrintReceipt}
                    />
                )
            }
        </div>
    )
}

export default CartSummary;
