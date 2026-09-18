import {useNavigate} from "react-router-dom";
import Button from "../../ui/Button.jsx";

const NotFound = () => {
    const navigate = useNavigate();
    return (
        <div className="flex min-h-[calc(100vh-5.5rem)] items-center justify-center px-4 py-10">
            <div className="w-full max-w-xl border-[3px] border-ink bg-surface p-10 text-center shadow-[4px_4px_0_#111827]">
                <p className="text-xs font-extrabold uppercase tracking-[0.2em] text-muted">Error</p>
                <h1 className="mt-2 text-8xl font-extrabold leading-none text-ink">404</h1>
                <h2 className="mt-4 text-3xl font-extrabold">Oops! Page not found</h2>
                <p className="mt-3 text-muted">
                    The page you're looking for doesn't exist or has been moved.
                </p>
                <Button className="mt-8" variant="primary" onClick={() => navigate('/')}>
                    Go to homepage
                </Button>
            </div>
        </div>
    )
}

export default NotFound;
