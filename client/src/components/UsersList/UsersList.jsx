import {useState} from "react";
import {deleteUser} from "../../Service/UserService.js";
import toast from "react-hot-toast";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const UsersList = ({users, setUsers}) => {
    const [searchTerm, setSearchTerm] = useState("");
    const [deletingId, setDeletingId] = useState(null);

    const filteredUsers = users.filter(user =>
        user.name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const deleteByUserId = async (id) => {
        if (deletingId) return;
        setDeletingId(id);
        try {
            await deleteUser(id);
            setUsers(prevUsers => prevUsers.filter(user => user.userId !== id));
            toast.success("User deleted");
        }catch (e) {
            console.error(e);
            toast.error(e.friendlyMessage || "Unable to delete user");
        } finally {
            setDeletingId(null);
        }
    }

    const roleLabel = (role) => {
        if (!role) return "User";
        return role.replace("ROLE_", "").replace(/_/g, " ");
    };

    return (
        <div className="space-y-4">
            <div className="flex border-2 border-ink bg-surface shadow-[2px_2px_0_#111827]">
                <input
                    type="text"
                    name="keyword"
                    id="keyword"
                    placeholder="Search by keyword"
                    className="w-full bg-transparent px-3 py-2.5 outline-none"
                    onChange={(e) => setSearchTerm(e.target.value)}
                    value={searchTerm}
                />
                <span className="flex items-center border-l-2 border-ink bg-primary/10 px-3">
                    <i className="bi bi-search"></i>
                </span>
            </div>
            {filteredUsers.length === 0 ? (
                <EmptyState title="No users found" description="Add a teammate or try another search." />
            ) : (
                <div className="overflow-x-auto">
                    <table className="nb-table min-w-[520px]">
                        <thead>
                        <tr>
                            <th>Name</th>
                            <th>Email</th>
                            <th>Role</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {filteredUsers.map((user, index) => (
                            <tr key={user.userId || index}>
                                <td className="font-extrabold">{user.name}</td>
                                <td>{user.email}</td>
                                <td>
                                    <Badge tone={(user.role || "").includes("ADMIN") ? "info" : "muted"}>
                                        {roleLabel(user.role)}
                                    </Badge>
                                </td>
                                <td>
                                    <Button
                                        variant="danger"
                                        size="sm"
                                        onClick={() => deleteByUserId(user.userId)}
                                        disabled={deletingId === user.userId}
                                        aria-label="Delete user"
                                    >
                                        <i className="bi bi-trash"></i>
                                    </Button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    )
}

export default UsersList;
