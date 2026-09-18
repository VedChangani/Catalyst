import './Print.css';
import Button from "../../ui/Button.jsx";

const ReceiptPopup = ({orderDetails, onClose, onPrint}) => {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/50 p-4">
            <div className="receipt-popup max-h-[90vh] w-full max-w-md overflow-y-auto border-[3px] border-ink bg-surface p-6 text-ink shadow-[4px_4px_0_#111827]">
                <div className="mb-4 text-center">
                    <i className="bi bi-check-circle-fill text-4xl text-success"></i>
                </div>
                <h3 className="mb-4 text-center text-2xl font-extrabold">Order Receipt</h3>
                <p>
                    <strong>Order ID:</strong> {orderDetails.orderId}
                </p>
                {orderDetails.customerName && (
                    <p>
                        <strong>Name:</strong> {orderDetails.customerName}
                    </p>
                )}
                {orderDetails.phoneNumber && (
                    <p>
                        <strong>Phone:</strong> {orderDetails.phoneNumber}
                    </p>
                )}
                <hr className="my-3 border-ink/20" />
                <h5 className="mb-3 font-extrabold">Items Ordered</h5>
                <div className="mb-4 max-h-48 overflow-y-auto">
                    {orderDetails.items.map((item, index) => (
                        <div key={index} className="mb-2 flex justify-between">
                            <span>{item.name} x{item.quantity}</span>
                            <span className="font-bold">₹{(item.price * item.quantity).toFixed(2)}</span>
                        </div>
                    ))}
                </div>
                <hr className="my-3 border-ink/20" />
                <div className="mb-2 flex justify-between">
                    <span>
                        <strong>Subtotal:</strong>
                    </span>
                    <span>₹{orderDetails.subtotal.toFixed(2)}</span>
                </div>
                <div className="mb-2 flex justify-between">
                    <span>
                        <strong>Tax (1%):</strong>
                    </span>
                    <span>₹{orderDetails.tax.toFixed(2)}</span>
                </div>
                <div className="mb-4 flex justify-between text-xl">
                    <span>
                        <strong>Grand Total:</strong>
                    </span>
                    <span className="font-extrabold">₹{orderDetails.grandTotal.toFixed(2)}</span>
                </div>
                <p>
                    <strong>Payment Method: </strong> {orderDetails.paymentMethod}
                </p>
                {
                    orderDetails.paymentMethod === "UPI" && (
                        <>
                            <p>
                                <strong>Razorpay Order ID: </strong> {orderDetails.razorpayOrderId}
                            </p>
                            <p>
                                <strong>Razorpay Payment ID: </strong> {orderDetails.razorpayPaymentId}
                            </p>
                        </>
                    )
                }
                <div className="mt-6 flex justify-end gap-3">
                    <Button variant="primary" onClick={onPrint}>Print Receipt</Button>
                    <Button variant="danger" onClick={onClose}>Close</Button>
                </div>
            </div>
        </div>
    )
}

export default ReceiptPopup;
