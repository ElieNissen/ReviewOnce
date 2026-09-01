export const LETTERBOXD_IMPORT_URL="https://letterboxd.com/import/";

export function isAndroid(userAgent:string){return /Android/i.test(userAgent)}

export function letterboxdImportTarget(userAgent:string){
 if(!isAndroid(userAgent))return LETTERBOXD_IMPORT_URL;
 return `intent://letterboxd.com/import/#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=${encodeURIComponent(LETTERBOXD_IMPORT_URL)};end`;
}
