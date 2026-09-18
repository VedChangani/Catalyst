import {useState} from "react";
import {addUser} from "../../Service/UserService.js";
import toast from "react-hot-toast";
import Input from "../../ui/Input.jsx";
import Button from "../../ui/Button.jsx";

const UserForm = ({setUsers}) => {
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState({
        name: "",
        email: "",
        password: "",
        role: "ROLE_USER"
    });

    const onChangeHandler = (e) => {
        const value = e.target.value;
        const name = e.target.name;
        setData((data) => ({ ...data, [name]: value }));
    }

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        setLoading(true);
        try {
            const response = await addUser(data);
            setUsers((prevUsers) => [...prevUsers, response.data]);
            toast.success("User Added");
            setData({
                name: "",
                email: "",
                password: "",
                role: "ROLE_USER",
            })
        } catch (e) {
            console.error(e);
            toast.error("Error adding user");
        } finally {
            setLoading(false);
        }
    }

    return (
        <form onSubmit={onSubmitHandler} className="space-y-4">
            <Input
                label="Name"
                type="text"
                name="name"
                id="name"
                placeholder="Jhon Doe"
                onChange={onChangeHandler}
                value={data.name}
                required
            />
            <Input
                label="Email"
                type="email"
                name="email"
                id="email"
                placeholder="yourname@example.com"
                onChange={onChangeHandler}
                value={data.email}
                required
            />
            <Input
                label="Password"
                type="password"
                name="password"
                id="password"
                placeholder="**************"
                onChange={onChangeHandler}
                value={data.password}
                required
            />
            <Button type="submit" className="w-full" disabled={loading}>
                {loading ? "Loading..." : "Save"}
            </Button>
        </form>
    )
}

export default UserForm;
