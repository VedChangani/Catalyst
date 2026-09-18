import {useContext, useRef, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import ReceiptPopup from "../ReceiptPopup/ReceiptPopup.jsx";
import {createOrder, cancelOrder, failPaymentOrder} from "../../Service/OrderService.js";
import toast from "react-hot-toast";
import {createRazorpayOrder, verifyPayment} from "../../Service/PaymentService.js";
import {AppConstants} from "../../util/constants.js";
import Button from "../../ui/Button.jsx";

// posMode (cashier/admin billing): billing name/phone are optional, an explicitly selected
// registered customer (posCustomer, chosen via the backend lookup) is sent by its userId, and a
// finished sale shows its receipt straight away and resets the cart and the customer selection.
const CartSummary = ({customerName, mobileNumber, setMobileNumber, setCustomerName,
                         posMode = false, posCustomer = null, onSaleFinished}) => {
    const {cartItems, itemsData, clearCart, refreshCatalog, auth} = useContext(AppContext);

    const [isProcessing, setIsProcessing] = useState(false);
    const [orderDetails, setOrderDetails] = useState(null);
    const [showPopup, setShowPopup] = useState(false);

    // Razorpay can fire more than one terminal event for a single checkout: the success handler
    // is followed by modal.ondismiss when the popup closes, and a payment.failed is likewise
    // followed by ondismiss. This ref marks a checkout attempt as already settled so only the
    // FIRST terminal event acts - without it a successful payment would immediately be followed
    // by a cancel call against the order that was just paid.
    const checkoutSettledRef = useRef(false);

    // Idempotency key of the checkout attempt in flight (sent as the Idempotency-Key header). The
    // same key is reused only for a retry of the SAME logical request - identical cart, billing
    // details, customer and payment method (tracked by checkoutSignatureRef) - so a network retry
    // is answered with the already-created order. Any change to the request gets a fresh key, and
    // the key is dropped as soon as the attempt is finished (see resetCheckoutKey). Refs, so
    // ordinary re-renders never disturb it.
    const checkoutKeyRef = useRef(null);
    const checkoutSignatureRef = useRef(null);

    const resetCheckoutKey = () => {
        checkoutKeyRef.current = null;
        checkoutSignatureRef.current = null;
    };

    const keyForRequest = (signature) => {
        if (!checkoutKeyRef.current || checkoutSignatureRef.current !== signature) {
            // crypto.randomUUID needs a secure context (https/localhost); fall back so checkout never breaks
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

    // Display/UX-only check against the latest fetched catalog data (mirrors CartItems.jsx) so
    // checkout isn't even attempted when the cart is already known to be stale. The backend
    // still re-validates and is the only authority that can actually reject an order.
    const hasKnownInventoryIssue = cartItems.some(cartItem => {
        const catalogItem = itemsData.find(item => item.itemId === cartItem.itemId);
        if (!catalogItem) return true;
        if (catalogItem.active !== true) return true;
        const availableQuantity = catalogItem.availableQuantity;
        return availableQuantity == null || cartItem.quantity > availableQuantity;
    });

    const clearAll = () => {
        resetCheckoutKey();
        setCustomerName("");
        setMobileNumber("");
        clearCart();
        if (onSaleFinished) onSaleFinished();
    }

    // POS only: a verified/paid sale is closed out immediately, so a second tap on Cash/UPI can
    // never bill the same cart twice.
    const finishPosSale = (paidOrder) => {
        setOrderDetails(paidOrder);
        setShowPopup(true);
        clearAll();
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
        if (isProcessing) return;
        if (!posMode && (!customerName || !mobileNumber)) {
            toast.error("Please enter customer details");
            return;
        }
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
        // Only itemId + quantity are sent for each cart line; name, price, subtotal, tax and
        // grandTotal are never client-authoritative - the server looks up prices from the item
        // catalog and computes the totals itself. totalAmount/tax/grandTotal above remain purely
        // for the on-screen summary.
        const orderData = posMode ? {
            // POS request contract: no channel/creator/price fields. customerUserId is present
            // only for an explicitly selected registered customer; absent = walk-in.
            ...(posCustomer ? {customerUserId: posCustomer.userId} : {}),
            ...(customerName.trim() ? {customerName: customerName.trim()} : {}),
            ...(mobileNumber ? {phoneNumber: mobileNumber} : {}),
            cartItems: cartItems.map(({itemId, quantity}) => ({itemId, quantity})),
            paymentMethod: paymentMode.toUpperCase()
        } : {
            customerName,
            phoneNumber: mobileNumber,
            cartItems: cartItems.map(({itemId, quantity}) => ({itemId, quantity})),
            paymentMethod: paymentMode.toUpperCase()
        }
        // The logical request, order-insensitive for cart lines. Compared with the previous
        // attempt's to decide whether this is a retry (same key) or a new request (new key).
        const signature = JSON.stringify({
            posMode,
            customer: posCustomer?.userId ?? null,
            name: customerName.trim(),
            phone: mobileNumber,
            method: paymentMode,
            lines: cartItems.map(({itemId, quantity}) => [itemId, quantity]).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)),
        });
        const idempotencyKey = keyForRequest(signature);
        let orderCreated = false;
        setIsProcessing(true);
        // Drop any previously verified order before starting a new attempt. Without this, a
        // successful earlier order would leave orderDetails populated and PAID, which would keep
        // "Place Order" enabled and let a receipt be shown for the WRONG order if this new
        // attempt is cancelled or fails verification.
        setOrderDetails(null);
        try {

            const response = await createOrder(orderData, auth.role !== "ROLE_USER", idempotencyKey);
            const savedData = response.data;
            // 201 = created now, 200 = the backend replayed the order of an earlier attempt with
            // this key. From here on the order exists, so this key must not be reused (below).
            orderCreated = true;
            if ((response.status === 201 || response.status === 200) && paymentMode === "cash") {
                resetCheckoutKey();
                toast.success("Cash received");
                if (posMode) {
                    finishPosSale(savedData);
                } else {
                    setOrderDetails(savedData);
                }
                setIsProcessing(false);
            } else if ((response.status === 201 || response.status === 200) && paymentMode === "upi") {
                const razorpayLoaded = await loadRazorpayScript();
                if (!razorpayLoaded) {
                    toast.error('Unable to load razorpay');
                    await handleOrderCancellation(savedData.orderId);
                    resetCheckoutKey();
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
                            resetCheckoutKey();
                            setIsProcessing(false);
                        }
                    },
                    prefill: {
                        name: customerName || posCustomer?.name,
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
                            resetCheckoutKey();
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
                    resetCheckoutKey();
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
            // Keep the key only while it is still useful: no reply at all (network error/timeout) or
            // a 5xx means the order may or may not exist, so the retry must send the SAME key and
            // let the backend answer. A definitive 4xx rejection created nothing, and once the
            // order exists a later failure (e.g. opening the payment step) ends this attempt.
            if (orderCreated || (error.response && error.response.status < 500)) {
                resetCheckoutKey();
            }
            if (error.response?.status === 409) {
                // A stock/inventory conflict from order creation - the backend's own message is
                // already customer-safe (see GlobalExceptionHandler/ConflictException), so prefer
                // it; fall back to a generic inventory-specific message if it's ever missing.
                // No order was created here, so there is nothing to cancel/fail, and orderDetails
                // was already cleared above - no false success, no receipt.
                toast.error(error.friendlyMessage
                    || "Some items are no longer available in the requested quantity. Please review your cart and try again.");
                // Best-effort refresh so the badges/limits in the catalog and cart reflect the
                // current stock right away, instead of the customer discovering it's still stale
                // only on their next attempt.
                refreshCatalog();
            } else {
                toast.error(error.friendlyMessage || "Payment processing failed");
            }
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
                if (posMode) {
                    finishPosSale(verifiedOrder);
                } else {
                    setOrderDetails(verifiedOrder);
                }
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
            toast.error(error.friendlyMessage
                ? `Payment verification failed: ${error.friendlyMessage}`
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

            {hasKnownInventoryIssue && (
                <p className="mt-4 border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">
                    Some items in your cart are no longer available in the requested quantity. Adjust your cart to continue.
                </p>
            )}
            <div className="mt-4 grid grid-cols-2 gap-3">
                <Button
                    variant="success"
                    className="w-full"
                    onClick={() => completePayment("cash")}
                    disabled={isProcessing || hasKnownInventoryIssue}
                >
                    {isProcessing ? "Processing...": "Cash"}
                </Button>
                <Button
                    variant="primary"
                    className="w-full"
                    onClick={() => completePayment("upi")}
                    disabled={isProcessing || hasKnownInventoryIssue}
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
