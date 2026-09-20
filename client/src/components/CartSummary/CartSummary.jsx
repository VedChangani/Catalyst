import {useContext, useRef, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import ReceiptPopup from "../ReceiptPopup/ReceiptPopup.jsx";
import {createOrder, cancelOrder, failPaymentOrder} from "../../Service/OrderService.js";
import toast from "react-hot-toast";
import {createRazorpayOrder, verifyPayment} from "../../Service/PaymentService.js";
import {buildCheckoutOptions} from "../../util/razorpayCheckout.js";
import {buildOnlineOrderRequest, buildPosOrderRequest} from "../../util/posOrderRequest.js";
import Button from "../../ui/Button.jsx";

// Online checkout (default): the customer is the logged-in account - no name/phone is collected or
// sent; the backend snapshots them from the account.
// posMode (cashier POS billing): billing name/phone are optional, an explicitly selected
// registered customer (posCustomer, chosen via the backend lookup) is sent by its userId, and a
// finished sale shows its receipt straight away and resets the cart and the customer selection.
const CartSummary = ({customerName = "", mobileNumber = "", setMobileNumber = () => {}, setCustomerName = () => {},
                         posMode = false, posCustomer = null, onSaleFinished,
                         hideWhenEmpty = false, onReceiptClose}) => {
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

    // POS only: Cash / UPI merely SELECT the payment method; nothing is created until the cashier
    // presses Place Order. (The online cart keeps its one-tap Cash/UPI buttons.)
    const [posMethod, setPosMethod] = useState(null);

    const clearAll = () => {
        resetCheckoutKey();
        setPosMethod(null);
        setCustomerName("");
        setMobileNumber("");
        clearCart();
        if (onSaleFinished) onSaleFinished();
    }

    // A verified/paid sale (POS or online) is closed out immediately, so a second tap on Cash/UPI can
    // never bill the same cart twice.
    const finishSale = (paidOrder) => {
        setOrderDetails(paidOrder);
        setShowPopup(true);
        clearAll();
        // The backend has committed this sale's stock. Re-sync the catalog from the server (never
        // subtract locally) so every product shows its authoritative availability right away.
        // Not awaited: the receipt must not wait on it.
        refreshCatalog();
    }

    const handlePrintReceipt = () => {
        window.print();
    }

    const loadRazorpayScript = () => {
        // Loaded once per page; later checkouts reuse it instead of adding another <script> tag.
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

    // Resolves true only when the backend confirmed the cancellation; a failure is never hidden.
    const handleOrderCancellation = async (orderId) => {
        try {
            await cancelOrder(orderId);
            return true;
        } catch (error) {
            console.error("Failed to cancel order:", error);
            return false;
        }
    }

    // A network/transport failure = no HTTP response at all (see apiClient: `!error.response`).
    // Explicit HTTP responses (400/401/403/404/409/5xx) are never treated as network failures.
    const isNetworkError = (error) => !!error && !error.response;

    // Checkout could not be started (Razorpay order creation or SDK open failed) while the local
    // PENDING_PAYMENT order exists: cancel it through the normal backend lifecycle (never delete),
    // and report honestly whether the cancellation itself succeeded.
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
        // Only itemId + quantity are sent for each cart line; name, price, subtotal, tax and
        // grandTotal are never client-authoritative - the server looks up prices from the item
        // catalog and computes the totals itself. totalAmount/tax/grandTotal above remain purely
        // for the on-screen summary.
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
            // A selected registered customer with no usable id: stop here rather than send a
            // request that the backend could only read as a walk-in sale.
            console.error(error);
            toast.error(error.message);
            return;
        }
        // The logical request, order-insensitive for cart lines. Compared with the previous
        // attempt's to decide whether this is a retry (same key) or a new request (new key).
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

                //create razorpay order — amount is resolved server-side from the local order's
                //grandTotal (see RazorpayServiceImpl), so only the local orderId is sent here.
                let razorpayResponse;
                try {
                    razorpayResponse = await createRazorpayOrder({currency: 'INR', orderId: savedData.orderId});
                } catch (error) {
                    console.error(error);
                    // Checkout cannot continue: cancel the orphaned local order (not delete).
                    await abortCheckoutStart(savedData.orderId);
                    resetCheckoutKey();
                    setIsProcessing(false);
                    return;
                }

                // New checkout attempt: nothing has settled it yet.
                checkoutSettledRef.current = false;

                // Built inside the try below, so an incomplete server response cancels the order like
                // any other failure to start Checkout.
                const buildOptions = () => buildCheckoutOptions({
                    razorpayOrder: razorpayResponse.data,
                    // Prefill comes from the saved order's own snapshot (the account's details for
                    // online orders), not from anything typed on this page.
                    prefill: {
                        name: savedData.customerName || customerName || posCustomer?.name,
                        contact: savedData.phoneNumber || mobileNumber,
                    },
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
                    onDismiss: async () => {
                        // Fires on every close, including after a successful payment or a
                        // payment.failed - only act if nothing has settled this attempt yet.
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
                    // SDK constructor/open() threw. Settle through the same ref so a stray
                    // late callback cannot also act; no new order is created.
                    if (checkoutSettledRef.current) return;
                    checkoutSettledRef.current = true;
                    await abortCheckoutStart(savedData.orderId);
                    resetCheckoutKey();
                    setIsProcessing(false);
                    return;
                }
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
            let paymentResponse;
            try {
                paymentResponse = await verifyPayment(paymentData);
            } catch (firstError) {
                // No reliable response (network/transport failure): the server may already have
                // verified and marked the order PAID. Retry exactly once with the SAME payload
                // (verification is idempotent server-side). Explicit HTTP errors are not retried.
                if (!isNetworkError(firstError)) throw firstError;
                paymentResponse = await verifyPayment(paymentData);
            }
            const verifiedOrder = paymentResponse.data;

            // Success requires BOTH a 2xx and a backend-reported PAID status. The status is the
            // server's own verdict after signature verification - we never infer PAID from the
            // Razorpay callback or from the HTTP status alone.
            if (paymentResponse.status === 200 && verifiedOrder?.orderStatus === "PAID") {
                toast.success("Payment successful");
                finishSale(verifiedOrder);
            } else {
                // Verification did not confirm payment. Leave orderDetails null so the receipt
                // stays unavailable; the order keeps whatever state the backend decided.
                setOrderDetails(null);
                toast.error("Payment could not be verified. Your order has not been marked paid.");
            }
        } catch (error) {
            console.error(error);
            setOrderDetails(null);
            if (isNetworkError(error)) {
                // Both attempts got no response. The outcome is UNKNOWN (the order may be PAID),
                // so do not say failed/cancelled, cancel, or reopen checkout.
                toast.error(`Payment status could not be confirmed. Check your order history (order #${savedOrder.orderId}) before trying again.`, {duration: 10000});
                return;
            }
            // A rejected verification (bad signature, mismatched Razorpay order, wrong owner,
            // invalid state) lands here. The backend remains the source of truth - we do not
            // mark anything paid client-side.
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

    // The customer cart page empties its cart the moment a sale is paid, but the receipt shown for
    // that sale lives here - so with nothing left to bill, render only the receipt.
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
