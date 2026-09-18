import {useState} from "react";

const SearchBox = ({onSearch}) => {
    const [searchText, setSearchText] = useState("");

    const handleInputChange = (e) => {
        const text = e.target.value;
        setSearchText(text);
        onSearch(text);
    }

    return (
        <div className="flex w-full max-w-sm border-2 border-ink bg-surface shadow-[2px_2px_0_#111827]">
            <input
                type="text"
                className="w-full bg-transparent px-3 py-2.5 outline-none"
                placeholder="Search items.."
                value={searchText}
                onChange={handleInputChange}
            />
            <span className="flex items-center border-l-2 border-ink bg-primary/10 px-3">
                <i className="bi bi-search"></i>
            </span>
        </div>
    )
}

export default SearchBox;
