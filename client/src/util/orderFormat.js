// Display helpers shared by the order screens (My Orders and the admin order list).

export const formatDate = (dateString) => {
    const options = {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
    }
    return new Date(dateString).toLocaleDateString('en-US', options);
}

export const orderStatusTone = (status) => {
    switch (status) {
        case "PAID": return "success";
        case "PENDING_PAYMENT": return "warning";
        case "PAYMENT_FAILED": return "danger";
        case "CANCELLED": return "muted";
        default: return "muted";
    }
};

export const paymentStatusTone = (status) => {
    switch (status) {
        case "COMPLETED": return "success";
        case "PENDING": return "warning";
        case "FAILED": return "danger";
        default: return "muted";
    }
};

// ONLINE / POS (null on legacy orders whose channel was never recorded).
export const channelLabel = (channel) => {
    switch (channel) {
        case "ONLINE": return "Online";
        case "POS": return "In store (POS)";
        default: return "Unknown";
    }
};

export const channelTone = (channel) => {
    switch (channel) {
        case "ONLINE": return "info";
        case "POS": return "warning";
        default: return "muted";
    }
};

export const statusLabel = (status) => (status || "").replaceAll("_", " ");
