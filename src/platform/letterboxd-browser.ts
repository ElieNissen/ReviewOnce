export const LETTERBOXD_IMPORT_URL="https://letterboxd.com/import/";

export function isAndroid(userAgent:string){return /Android/i.test(userAgent)}

export function androidBrowserPackage(userAgent:string){
 if(/Firefox|FxiOS/i.test(userAgent))return"org.mozilla.firefox";
 if(/EdgA/i.test(userAgent))return"com.microsoft.emmx";
 if(/SamsungBrowser/i.test(userAgent))return"com.sec.android.app.sbrowser";
 if(/OPR/i.test(userAgent))return"com.opera.browser";
 return"com.android.chrome";
}

export function letterboxdImportTarget(userAgent:string){
 if(!isAndroid(userAgent))return LETTERBOXD_IMPORT_URL;
 return `intent://letterboxd.com/import/#Intent;scheme=https;package=${androidBrowserPackage(userAgent)};S.browser_fallback_url=${encodeURIComponent(LETTERBOXD_IMPORT_URL)};end`;
}
