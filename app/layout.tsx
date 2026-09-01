import type { Metadata, Viewport } from "next";
import "./globals.css";
export const metadata:Metadata={title:"ReviewOnce — SensCritique vers Letterboxd",description:"Transférez vos films, notes et critiques vers Letterboxd sans double saisie.",applicationName:"ReviewOnce",manifest:"/manifest.webmanifest",themeColor:"#f4f1e9",icons:{icon:"/favicon.svg",apple:"/favicon.svg"},openGraph:{title:"ReviewOnce",description:"Films, notes et critiques sans double saisie.",type:"website"},other:{"codex-preview":"development"}};
export const viewport:Viewport={width:"device-width",initialScale:1,viewportFit:"cover",themeColor:"#f7f7f5"};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="fr"><body>{children}</body></html>}
