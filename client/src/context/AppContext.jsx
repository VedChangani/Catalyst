import {createContext, useEffect, useRef, useState} from "react";
import toast from "react-hot-toast";
import {fetchCategories} from "../Service/CategoryService.js";
import {fetchItems} from "../Service/ItemService.js";
import {cartQuantityTotal} from "../util/cart.js";
import {hasSession} from "../util/authSession.js";
import {createLatestOnly} from "../util/latestOnly.js";

export const AppContext = createContext(null);

export const AppContextProvider = (props) => {

    const [categories, setCategories] = useState([]);
    const [itemsData, setItemsData] = useState([]);
    const [auth, setAuth] = useState(() => ({
        token: localStorage.getItem("token"),
        role: localStorage.getItem("role"),
    }));
    const [cartItems, setCartItems] = useState([]);
    const [isCatalogLoading, setIsCatalogLoading] = useState(true);

    const addToCart = (item) => {
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

    const catalogRequests = useRef(createLatestOnly());

    const loadCatalog = async () => {
        const isCurrent = catalogRequests.current.begin();
        setIsCatalogLoading(true);
        try {
            const [categoryResponse, itemResponse] = await Promise.all([fetchCategories(), fetchItems()]);
            if (isCurrent()) {
                setCategories(categoryResponse.data);
                setItemsData(itemResponse.data);
            }
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to load the catalog");
        } finally {
            if (isCurrent()) {
                setIsCatalogLoading(false);
            }
        }
    }

    const refreshCatalog = async () => {
        const isCurrent = catalogRequests.current.begin();
        try {
            const [categoryResponse, itemResponse] = await Promise.all([fetchCategories(), fetchItems()]);
            if (isCurrent()) {
                setCategories(categoryResponse.data);
                setItemsData(itemResponse.data);
            }
        } catch (error) {
            console.error(error);
            toast.error("Stock levels could not be refreshed. Reload the page to see current stock.");
        }
    }

    useEffect(() => {
        const token = localStorage.getItem("token");
        const role = localStorage.getItem("role");
        if (hasSession(token, role)) {
            setAuth({token, role});
            loadCatalog();
        } else {
            setIsCatalogLoading(false);
        }
    }, []);

    const setAuthData = (token, role) => {
        setAuth({token, role});
        if (hasSession(token, role)) {
            loadCatalog();
        } else {
            catalogRequests.current.begin();
            setCategories([]);
            setItemsData([]);
            setIsCatalogLoading(false);
        }
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
        cartCount: cartQuantityTotal(cartItems),
        removeFromCart,
        updateQuantity,
        clearCart,
        refreshCatalog
    }

    return <AppContext.Provider value={contextValue}>
        {props.children}
    </AppContext.Provider>
}