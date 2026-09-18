import {createContext, useEffect, useState} from "react";
import toast from "react-hot-toast";
import {fetchCategories} from "../Service/CategoryService.js";
import {fetchItems} from "../Service/ItemService.js";

export const AppContext = createContext(null);

export const AppContextProvider = (props) => {

    const [categories, setCategories] = useState([]);
    const [itemsData, setItemsData] = useState([]);
    const [auth, setAuth] = useState({token: null, role: null});
    const [cartItems, setCartItems] = useState([]);
    const [isCatalogLoading, setIsCatalogLoading] = useState(true);

    const addToCart = (item) => {
        // Identity is itemId, not name (matches removeFromCart/updateQuantity below, and the
        // availability logic in Item.jsx/CartItems.jsx) - two distinct products that happen to
        // share a display name must stay as separate cart lines, never merged.
        const existingItem = cartItems.find(cartItem => cartItem.itemId === item.itemId);
        if (existingItem) {
            setCartItems(cartItems.map(cartItem => cartItem.itemId === item.itemId ? {...cartItem, quantity: cartItem.quantity + 1} : cartItem));
        } else {
            setCartItems([...cartItems, {...item, quantity: 1}]);
        }
    }

    const removeFromCart = (itemId) => {
        setCartItems(cartItems.filter(item => item.itemId !== itemId));
    }

    const updateQuantity = (itemId, newQuantity) => {
        setCartItems(cartItems.map(item => item.itemId === itemId ? {...item, quantity: newQuantity} : item));
    }

    const loadCatalog = async () => {
        setIsCatalogLoading(true);
        try {
            const [categoryResponse, itemResponse] = await Promise.all([fetchCategories(), fetchItems()]);
            setCategories(categoryResponse.data);
            setItemsData(itemResponse.data);
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to load the catalog");
        } finally {
            setIsCatalogLoading(false);
        }
    }

    useEffect(() => {
        const token = localStorage.getItem("token");
        const role = localStorage.getItem("role");
        // The catalog endpoints require an authenticated USER/ADMIN, so there is nothing to
        // fetch (and nothing to show a loading/error state for) until a session exists.
        if (token && role) {
            setAuth({token, role});
            loadCatalog();
        } else {
            setIsCatalogLoading(false);
        }
    }, []);

    const setAuthData = (token, role) => {
        setAuth({token, role});
        loadCatalog();
    }

    const clearCart = () => {
        setCartItems([]);
    }

    const contextValue = {
        categories,
        setCategories,
        auth,
        setAuthData,
        itemsData,
        setItemsData,
        isCatalogLoading,
        addToCart,
        cartItems,
        removeFromCart,
        updateQuantity,
        clearCart,
        // Re-fetches items/categories so a stale availableQuantity/active value (e.g. after a
        // checkout is rejected with a stock conflict) is replaced with the current catalog state.
        refreshCatalog: loadCatalog
    }

    return <AppContext.Provider value={contextValue}>
        {props.children}
    </AppContext.Provider>
}