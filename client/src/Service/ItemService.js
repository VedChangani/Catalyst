import apiClient from "../util/apiClient.js";

export const addItem = async (item) => {
    return await apiClient.post(`/admin/items`, item);
}

export const deleteItem = async (itemId) => {
    return await apiClient.delete(`/admin/items/${itemId}`);
}

export const fetchItems = async () => {
    return await apiClient.get('/items');
}

// General metadata edit - never sends stockQuantity/reservedQuantity; stock only changes via
// adjustStock below.
export const updateItem = async (itemId, payload) => {
    return await apiClient.put(`/admin/items/${itemId}`, payload);
}

export const adjustStock = async (itemId, delta) => {
    return await apiClient.patch(`/admin/items/${itemId}/stock`, {delta});
}
