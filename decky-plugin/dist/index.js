const manifest = { "name": "Minecraft HUD" };
const API_VERSION = 2;
const internalAPIConnection = window.__DECKY_SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED_deckyLoaderAPIInit;
if (!internalAPIConnection) {
    throw new Error('[@decky/api]: Failed to connect to loader API.');
}
let api;
try {
    api = internalAPIConnection.connect(API_VERSION, manifest.name);
} catch {
    api = internalAPIConnection.connect(1, manifest.name);
}
const call = api.call;
const toaster = api.toaster;
const definePlugin = (fn) => (...args) => fn(...args);

const getStatus = () => call("get_status");
const loadHud = () => call("load_hud");
const unloadHud = () => call("unload_hud");
const restartHud = () => call("restart_hud");

const h = SP_JSX.jsx;
const hs = SP_JSX.jsxs;

function Content() {
    const [status, setStatus] = SP_REACT.useState({ active: false, connected: false, description: "Checking..." });
    const [busy, setBusy] = SP_REACT.useState(false);

    const refresh = SP_REACT.useCallback(async () => {
        try {
            const s = await getStatus();
            if (s) setStatus(s);
        } catch (e) {
            console.error("[mc-hud] refresh failed:", e);
        }
    }, []);

    SP_REACT.useEffect(() => {
        refresh();
        const interval = setInterval(refresh, 2000);
        return () => clearInterval(interval);
    }, [refresh]);

    const act = async (fn, okMsg) => {
        setBusy(true);
        try {
            const res = await fn();
            if (res) setStatus(res);
            if (okMsg) toaster.toast({ title: "Minecraft HUD", body: okMsg });
        } catch (e) {
            toaster.toast({ title: "Minecraft HUD Error", body: String(e) });
        } finally {
            setBusy(false);
        }
    };

    return hs(SP_JSX.Fragment, {
        children: [
            hs(DFL.PanelSection, {
                title: "Second Screen Controls",
                children: [
                    h(DFL.PanelSectionRow, {
                        children: h(DFL.Field, {
                            label: "HUD State",
                            description: status.active 
                                ? (status.connected ? "● Running (Connected to MC)" : "○ Running (Waiting for MC)")
                                : "Stopped (Trackpad Active)"
                        })
                    }),
                    h(DFL.PanelSectionRow, {
                        children: h(DFL.ButtonItem, {
                            layout: "below",
                            disabled: busy,
                            onClick: () => status.active ? act(unloadHud, "HUD unloaded — bottom screen restored") : act(loadHud, "HUD loaded onto bottom screen"),
                            children: status.active ? "Unload HUD (Restore Trackpad)" : "Load HUD to Second Screen"
                        })
                    }),
                    status.active && h(DFL.PanelSectionRow, {
                        children: h(DFL.ButtonItem, {
                            layout: "below",
                            disabled: busy,
                            onClick: () => act(restartHud, "HUD restarted"),
                            children: "Restart HUD"
                        })
                    })
                ]
            })
        ]
    });
}

const index = definePlugin((serverApi) => {
    return {
        name: "Minecraft HUD",
        content: h(Content, {}),
        icon: h("svg", {
            xmlns: "http://www.w3.org/2000/svg",
            viewBox: "0 0 24 24",
            width: "24",
            height: "24",
            fill: "none",
            stroke: "currentColor",
            strokeWidth: "2",
            strokeLinecap: "round",
            strokeLinejoin: "round",
            children: [
                h("rect", { x: "2", y: "3", width: "20", height: "14", rx: "2" }),
                h("line", { x1: "8", y1: "21", x2: "16", y2: "21" }),
                h("line", { x1: "12", y1: "17", x2: "12", y2: "21" })
            ]
        }),
        onDismount() {}
    };
});

export { index as default };
