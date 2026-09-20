const CustomerForm = ({customerName, mobileNumber, setMobileNumber, setCustomerName, optional = false}) => {
    return (
        <div className="space-y-3">
            <div>
                <label htmlFor="customerName" className="mb-1 block text-xs font-extrabold uppercase tracking-wide">Customer name{optional ? " (optional)" : ""}</label>
                <input
                    type="text"
                    className="nb-input py-2 text-sm"
                    id="customerName"
                    onChange={(e) => setCustomerName(e.target.value)}
                    value={customerName}
                    required={!optional}
                />
            </div>
            <div>
                <label htmlFor="mobileNumber" className="mb-1 block text-xs font-extrabold uppercase tracking-wide">Mobile number{optional ? " (optional)" : ""}</label>
                <input
                    type="text"
                    className="nb-input py-2 text-sm"
                    id="mobileNumber"
                    onChange={(e) => setMobileNumber(e.target.value)}
                    value={mobileNumber}
                    required={!optional}
                    inputMode="numeric"
                    maxLength={10}
                />
            </div>
        </div>
    )
}

export default CustomerForm;
