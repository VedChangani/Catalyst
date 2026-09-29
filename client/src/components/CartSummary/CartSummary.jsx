import {useContext, useRef, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import ReceiptPopup from "../ReceiptPopup/ReceiptPopup.jsx";
import {createOrder, cancelOrder, failPaymentOrder} from "../../Service/OrderService.js";
import toast from "react-hot-toast";
import {createRazorpayOrder, verifyPayment} from "../../Service/PaymentService.js";
import {buildCheckoutOptions} from "../../util/razorpayCheckout.js";
import {buildOnlineOrderRequest, buildPosOrderRequest} from "../../util/posOrderRequest.js";
import Button from "../../ui/Button.jsx";

const CartSummary = ({customerName = "", mobileNumber = "", setMobileNumber = () => {}, setCustomerName = () => {},
                         posMode = false, posCustomer = null, onSaleFinished,
                         hideWhenEmpty = false, onReceiptClose}) => {
    const {cartItems, itemsData, clearCart, refreshCatalog, auth} = useContext(AppContext);

    const [isProcessing, setIsProcessing] = useState(false);
    const [orderDetails, setOrderDetails] = useState(null);
    const [showPopup, setShowPopup] = useState(false);

    const checkoutSettledRef = useRef(false);

    const checkoutKeyRef = useRef(null);
    const checkoutSignatureRef = useRef(null);

    const resetCheckoutKey = () => {
        checkoutKeyRef.current = null;
        checkoutSignatureRef.current = null;
    };

    const keyForRequest = (signature) => {
        if (!checkoutKeyRef.current || checkoutSignatureRef.current !== signature) {
            checkoutKeyRef.current = typeof crypto !== "undefined" && crypto.randomUUID
                ? crypto.randomUUID()
                : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}-${Math.random().toString(36).slice(2, 12)}`;
            checkoutSignatureRef.current = signature;
        }
        return checkoutKeyRef.current;
    };

    const totalAmount = cartItems.reduce((total, item) => total + item.price * item.quantity, 0);
    const tax = totalAmount * 0.01;
    const grandTotal = totalAmount + tax;

    const hasKnownInventoryIssue = cartItems.some(cartItem => {
        const catalogItem = itemsData.find(item => item.itemId === cartItem.itemId);
        if (!catalogItem) return true;
        if (catalogItem.active !== true) return true;
        const availableQuantity = catalogItem.availableQuantity;
        return availableQuantity == null || cartItem.quantity > availableQuantity;
    });

    const [posMethod, setPosMethod] = useState(null);

    const clearAll = () => {
        resetCheckoutKey();
        setPosMethod(null);
        setCustomerName("");
        setMobileNumber("");
        clearCart();
        if (onSaleFinished) onSaleFinished();
    }

    const finishSale = (paidOrder) => {
        setOrderDetails(paidOrder);
        setShowPopup(true);
        clearAll();
        refreshCatalog();
    }

    const handlePrintReceipt = () => {
        window.print();
    }

    const loadRazorpayScript = () => {
        if (typeof window !== "undefined" && window.Razorpay) {
            return Promise.resolve(true);
        }
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
            return true;
        } catch (error) {
            console.error("Failed to cancel order:", error);
            return false;
        }
    }

    const isNetworkError = (error) => !!error && !error.response;

    const abortCheckoutStart = async (orderId) => {
        const cancelled = await handleOrderCancellation(orderId);
        if (cancelled) {
            toast.error("Payment could not be started and the order was cancelled. Please try again.");
        } else {
            toast.error(`Payment could not be started, and order #${orderId} may still be pending. Please check your order history.`, {duration: 8000});
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
        if (isProcessing) return;
        if (posMode && mobileNumber && !/^[0-9]{10}$/.test(mobileNumber)) {
            toast.error("Mobile number must be exactly 10 digits");
            return;
        }

        if (cartItems.length === 0) {
            toast.error("Your cart is empty");
            return;
        }
        if (hasKnownInventoryIssue) {
            toast.error("Some items in your cart are no longer available in the requested quantity. Please review your cart and try again.");
            return;
        }
        let orderData;
        try {
            orderData = posMode
                ? buildPosOrderRequest({
                    customer: posCustomer,
                    customerName,
                    phoneNumber: mobileNumber,
                    paymentMethod: paymentMode.toUpperCase(),
                    cartItems,
                })
                : buildOnlineOrderRequest({paymentMethod: paymentMode.toUpperCase(), cartItems});
        } catch (error) {
            console.error(error);
            toast.error(error.message);
            return;
        }
        const signature = JSON.stringify({
            posMode,
            customer: posCustomer?.userId ?? null,
            name: posMode ? customerName.trim() : "",
            phone: posMode ? mobileNumber : "",
            method: paymentMode,
            lines: cartItems.map(({itemId, quantity}) => [itemId, quantity]).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)),
        });
        const idempotencyKey = keyForRequest(signature);
        let orderCreated = false;
        setIsProcessing(true);
        setOrderDetails(null);
        try {

            const response = await createOrder(orderData, auth.role !== "ROLE_USER", idempotencyKey);
            const savedData = response.data;
            orderCreated = true;
            if ((response.status === 201 || response.status === 200) && paymentMode === "cash") {
                resetCheckoutKey();
                toast.success("Cash received");
                finishSale(savedData);
                setIsProcessing(false);
            } else if ((response.status === 201 || response.status === 200) && paymentMode === "upi") {
                const razorpayLoaded = await loadRazorpayScript();
                if (!razorpayLoaded) {
                    await abortCheckoutStart(savedData.orderId);
                    resetCheckoutKey();
                    setIsProcessing(false);
                    return;
                }

                let razorpayResponse;
                try {
                    razorpayResponse = await createRazorpayOrder({currency: 'INR', orderId: savedData.orderId});
                } catch (error) {
                    console.error(error);
                    await abortCheckoutStart(savedData.orderId);
                    resetCheckoutKey();
                    setIsProcessing(false);
                    return;
                }

                checkoutSettledRef.current = false;

                const buildOptions = () => buildCheckoutOptions({
                    razorpayOrder: razorpayResponse.data,
                    prefill: {
                        name: savedData.customerName || customerName || posCustomer?.name,
                        contact: savedData.phoneNumber || mobileNumber,
                    },
                    handler: async function (response) {
                        if (checkoutSettledRef.current) return;
                        checkoutSettledRef.current = true;
                        try {
                            await verifyPaymentHandler(response, savedData);
                        } finally {
                            resetCheckoutKey();
                            setIsProcessing(false);
                        }
                    },
                    onDismiss: async () => {
                        if (checkoutSettledRef.current) return;
                        checkoutSettledRef.current = true;
                        const cancelled = await handleOrderCancellation(savedData.orderId);
                        resetCheckoutKey();
                        if (cancelled) {
                            toast.error("Payment cancelled");
                        } else {
                            toast.error(`Payment was not completed, but order #${savedData.orderId} could not be cancelled automatically. Please check your order history.`, {duration: 8000});
                        }
                        setIsProcessing(false);
                    },
                });
                try {
                    const rzp = new window.Razorpay(buildOptions());
                    rzp.on("payment.failed", async (response) => {
                        if (checkoutSettledRef.current) return;
                        checkoutSettledRef.current = true;
                        await handlePaymentFailure(savedData.orderId);
                        resetCheckoutKey();
                        toast.error("Payment failed");
                        console.error(response.error.description);
                        setIsProcessing(false);
                    });
                    rzp.open();
                } catch (error) {
                    console.error(error);
                    if (checkoutSettledRef.current) return;
                    checkoutSettledRef.current = true;
                    await abortCheckoutStart(savedData.orderId);
                    resetCheckoutKey();
                    setIsProcessing(false);
                    return;
                }
            }
        }catch(error) {
            console.error(error);
            if (orderCreated || (error.response && error.response.status < 500)) {
                resetCheckoutKey();
            }
            if (error.response?.status === 409) {
                toast.error(error.friendlyMessage
                    || "Some items are no longer available in the requested quantity. Please review your cart and try again.");
                refreshCatalog();
            } else {
                toast.error(error.friendlyMessage || "Payment processing failed");
            }
            setIsProcessing(false);
        }
    }

    const verifyPaymentHandler = async (response, savedOrder) => {
        const paymentData = {
            razorpayOrderId: response.razorpay_order_id,
            razorpayPaymentId: response.razorpay_payment_id,
            razorpaySignature: response.razorpay_signature,
            orderId: savedOrder.orderId
        };
        try {
            let paymentResponse;
            try {
                paymentResponse = await verifyPayment(paymentData);
            } catch (firstError) {
                if (!isNetworkError(firstError)) throw firstError;
                paymentResponse = await verifyPayment(paymentData);
            }
            const verifiedOrder = paymentResponse.data;

            if (paymentResponse.status === 200 && verifiedOrder?.orderStatus === "PAID") {
                toast.success("Payment successful");
                finishSale(verifiedOrder);
            } else {
                setOrderDetails(null);
                toast.error("Payment could not be verified. Your order has not been marked paid.");
            }
        } catch (error) {
            console.error(error);
            setOrderDetails(null);
            if (isNetworkError(error)) {
                toast.error(`Payment status could not be confirmed. Check your order history (order #${savedOrder.orderId}) before trying again.`, {duration: 10000});
                return;
            }
            toast.error(error.friendlyMessage
                ? `Payment verification failed: ${error.friendlyMessage}`
                : "Payment verification failed. Your order has not been marked paid.");
        }
    };

    const receiptPopup = showPopup && orderDetails && (
        <ReceiptPopup
            orderDetails={{
                ...orderDetails,
                razorpayOrderId: orderDetails.paymentDetails?.razorpayOrderId,
                razorpayPaymentId: orderDetails.paymentDetails?.razorpayPaymentId,
            }}
            onClose={() => {
                setShowPopup(false);
                if (onReceiptClose) onReceiptClose();
            }}
            onPrint={handlePrintReceipt}
        />
    );

    const isCartEmpty = cartItems.length === 0;

    if (hideWhenEmpty && isCartEmpty) {
        return <>{receiptPopup}</>;
    }

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

            {hasKnownInventoryIssue && (
                <p role="alert" className="mt-4 border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">
                    Some items in your cart are no longer available in the requested quantity. Adjust your cart to continue.
                </p>
            )}
            <div className="mt-4 grid grid-cols-2 gap-3">
                <Button
                    variant={posMode && posMethod !== "cash" ? "secondary" : "success"}
                    className="w-full"
                    onClick={() => posMode ? setPosMethod("cash") : completePayment("cash")}
                    aria-pressed={posMode ? posMethod === "cash" : undefined}
                    disabled={isProcessing || (!posMode && (hasKnownInventoryIssue || isCartEmpty))}
                >
                    {!posMode && isProcessing ? "Processing...": "Cash"}
                </Button>
                <Button
                    variant={posMode && posMethod !== "upi" ? "secondary" : "primary"}
                    className="w-full"
                    onClick={() => posMode ? setPosMethod("upi") : completePayment("upi")}
                    aria-pressed={posMode ? posMethod === "upi" : undefined}
                    disabled={isProcessing || (!posMode && (hasKnownInventoryIssue || isCartEmpty))}
                >
                    {!posMode && isProcessing ? "Processing...": "UPI"}
                </Button>
            </div>
            {posMode && !posMethod && !isCartEmpty && (
                <p className="mt-2 text-xs font-bold text-muted">Select Cash or UPI, then press Place Order.</p>
            )}
            {isProcessing && (
                <p role="status" className="mt-3 text-sm font-bold text-muted">
                    Processing your order - please don't close this page.
                </p>
            )}
            {posMode && (
                <div className="mt-3">
                    <Button
                        variant="dark"
                        className="w-full"
                        onClick={() => completePayment(posMethod)}
                        disabled={isProcessing || !posMethod || hasKnownInventoryIssue || isCartEmpty}
                    >
                        {isProcessing ? "Processing..." : "Place Order"}
                    </Button>
                </div>
            )}
            {receiptPopup}
        </div>
    )
}

export default CartSummary;
