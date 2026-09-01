import test from"node:test";
import assert from"node:assert/strict";
import{buildOfficialImports,buildSyncQueue,syncKey}from"../src/domain/sync.ts";
import{compareLibraries}from"../src/domain/compare.ts";
import type{Film}from"../src/domain/film.ts";
import{LETTERBOXD_IMPORT_URL,androidBrowserPackage,letterboxdImportTarget}from"../src/platform/letterboxd-browser.ts";

const film=(overrides:Partial<Film>={}):Film=>({
 id:"done-1",tmdbId:"123",title:"Film, with comma",year:"2025",rating:8,watchedDate:"2025-08-15",
 review:'Une critique avec "guillemets".',match:"ready",missing:["film"],wished:false,...overrides
});

test("opens the normal Letterboxd import page outside Android",()=>{
 assert.equal(letterboxdImportTarget("Mozilla/5.0 (Macintosh)"),LETTERBOXD_IMPORT_URL);
});

test("forces Chrome for the Letterboxd import page on Android",()=>{
 const target=letterboxdImportTarget("Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 Chrome/140 Mobile");
 assert.match(target,/^intent:\/\/letterboxd\.com\/import\//);
 assert.match(target,/package=com\.android\.chrome/);
 assert.match(target,/browser_fallback_url=https%3A%2F%2Fletterboxd\.com%2Fimport%2F/);
});

test("keeps the current supported Android browser",()=>{
 assert.equal(androidBrowserPackage("Android Firefox/142"),"org.mozilla.firefox");
 assert.equal(androidBrowserPackage("Android EdgA/140"),"com.microsoft.emmx");
 assert.equal(androidBrowserPackage("Android SamsungBrowser/28"),"com.sec.android.app.sbrowser");
 assert.match(letterboxdImportTarget("Android Firefox/142"),/package=org\.mozilla\.firefox/);
});

test("builds Letterboxd diary CSV with exact TMDB fields and escaping",()=>{
 const result=buildOfficialImports([film()]);
 assert.equal(result.diaryCount,1);
 assert.match(result.diary,/tmdbID,Title,Year,Rating10,WatchedDate,Review/);
 assert.match(result.diary,/123,"Film, with comma",2025,8,2025-08-15,"Une critique avec ""guillemets""\."/);
});

test("keeps watchlist in its own official import",()=>{
 const result=buildOfficialImports([film({id:"wish-1",wished:true,rating:null,watchedDate:"",review:"",missing:["watchlist"]})]);
 assert.equal(result.diaryCount,0);
 assert.equal(result.watchlistCount,1);
 assert.match(result.watchlist,/tmdbID,Title,Year/);
});

test("exports only missing fields for an existing Letterboxd film",()=>{
 const result=buildOfficialImports([film({missing:["review"]})]);
 assert.match(result.diary,/123,"Film, with comma",2025,,,"Une critique/);
 assert.doesNotMatch(result.diary,/,8,2025-08-15,/);
});

test("exports an identified film even when its Letterboxd log was not verified",()=>{
 const result=buildOfficialImports([film({match:"review"})]);
 assert.equal(result.diaryCount,1);
});

test("does not export an unidentified film",()=>{
 const result=buildOfficialImports([film({tmdbId:undefined})]);
 assert.equal(result.diaryCount,0);
});

test("queue keys change with source values and completed actions remain idempotent",()=>{
 const original=film(),changed=film({rating:9}),key=syncKey(original);
 assert.notEqual(key,syncKey(changed));
 assert.equal(buildSyncQueue([original],new Set([key]))[0].status,"synced");
 assert.equal(buildSyncQueue([changed],new Set([key]))[0].status,"pending");
});

test("compares watchlists separately from watched films",()=>{
 const source=film({id:"wish-1",wished:true,rating:null,watchedDate:"",review:""});
 const watchedOnLetterboxd=film({id:"lb-done",match:"complete"});
 const missing=compareLibraries([source],[watchedOnLetterboxd]);
 assert.deepEqual(missing[0].missing,["watchlist"]);
 assert.equal(missing[0].match,"ready");

 const wishedOnLetterboxd=film({id:"lb-wish",wished:true,match:"complete"});
 const complete=compareLibraries([source],[wishedOnLetterboxd]);
 assert.equal(complete[0].match,"complete");
});

test("marks an unidentified or partial absence for review",()=>{
 const unidentified=film({tmdbId:undefined,letterboxdUrl:undefined});
 assert.equal(compareLibraries([unidentified],[])[0].match,"review");
 assert.equal(compareLibraries([film()],[],[],true)[0].match,"review");
});
