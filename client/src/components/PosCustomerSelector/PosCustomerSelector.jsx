import {useEffect, useState} from "react";
import {searchPosCustomers} from "../../Service/PosService.js";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";

const MIN_SEARCH_LENGTH = 2;
const DEBOUNCE_MS = 350;

// Walk-in by default. The cashier may explicitly pick an existing registered customer; the
// choice is identified by the backend-provided userId only.
const PosCustomerSelector = ({selectedCustomer, onSelect, onClear, disabled = false}) => {
    const [query, setQuery] = useState("");
    const [results, setResults] = useState([]);
    const [searching, setSearching] = useState(false);
    const [searchError, setSearchError] = useState(null);

    const term = query.trim();
    const searchable = term.length >= MIN_SEARCH_LENGTH;

    useEffect(() => {
        if (!searchable) {
            return undefined;
        }
        // Debounced; each new term aborts the previous request so a slow older response can
        // never replace the results of what the cashier typed last.
        const controller = new AbortController();
        const timer = setTimeout(() => {
            setSearching(true);
            setSearchError(null);
            searchPosCustomers(term, controller.signal)
                .then((response) => setResults(response.data))
                .catch((error) => {
                    if (error.code === "ERR_CANCELED") {
                        return;
                    }
                    console.error(error);
                    setResults([]);
                    setSearchError(error.friendlyMessage || "Unable to search customers");
                })
                .finally(() => {
                    if (!controller.signal.aborted) {
                        setSearching(false);
                    }
                });
        }, DEBOUNCE_MS);
        return () => {
            clearTimeout(timer);
            controller.abort();
        };
    }, [term, searchable]);

    const choose = (customer) => {
        setQuery("");
        setResults([]);
        onSelect(customer);
    };

    if (selectedCustomer) {
        return (
            <div className="space-y-2">
                <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-extrabold uppercase tracking-wide">Customer</span>
                    <Badge tone="success">Registered</Badge>
                </div>
                <div className="border-2 border-ink bg-paper p-2">
                    <p className="font-extrabold">{selectedCustomer.name}</p>
                    <p className="text-xs text-muted">{selectedCustomer.email}</p>
                </div>
                <Button variant="secondary" size="sm" className="w-full" onClick={onClear} disabled={disabled}>
                    Remove · sell as walk-in
                </Button>
            </div>
        );
    }

    return (
        <div className="space-y-2">
            <div className="flex items-center justify-between gap-2">
                <label htmlFor="pos-customer-search" className="text-xs font-extrabold uppercase tracking-wide">
                    Customer
                </label>
                <Badge tone="muted">Walk-in</Badge>
            </div>
            <input
                id="pos-customer-search"
                type="search"
                className="nb-input py-2 text-sm"
                placeholder="Find a registered customer (name or email)"
                maxLength={100}
                value={query}
                disabled={disabled}
                onChange={(event) => setQuery(event.target.value)}
                autoComplete="off"
            />
            {searchable && searching && (
                <p className="text-xs font-bold text-muted" role="status">Searching...</p>
            )}
            {searchable && !searching && searchError && (
                <p className="border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">{searchError}</p>
            )}
            {searchable && !searching && !searchError && results.length === 0 && (
                <p className="text-xs font-bold text-muted">No registered customer found. Continue as walk-in.</p>
            )}
            {searchable && !searching && results.length > 0 && (
                <ul className="max-h-40 overflow-y-auto border-2 border-ink bg-surface">
                    {results.map((customer) => (
                        <li key={customer.userId} className="border-b border-ink/10 last:border-b-0">
                            <button
                                type="button"
                                className="block w-full px-3 py-2 text-left hover:bg-primary/10"
                                onClick={() => choose(customer)}
                            >
                                <span className="block font-bold">{customer.name}</span>
                                <span className="block text-xs text-muted">{customer.email}</span>
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
};

export default PosCustomerSelector;
