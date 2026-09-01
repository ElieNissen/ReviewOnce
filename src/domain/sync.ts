import type{Film,MissingField}from"./film";

export type SyncStatus="pending"|"needs-review"|"exported"|"synced"|"failed"|"session-expired";
export type SyncOperation="upsert-log"|"add-watchlist";
export type SyncItem={
 key:string;
 filmId:string;
 tmdbId?:string;
 title:string;
 operation:SyncOperation;
 fields:MissingField[];
 status:SyncStatus;
};

const stableHash=(value:string)=>{
 let hash=2166136261;
 for(let index=0;index<value.length;index++){hash^=value.charCodeAt(index);hash=Math.imul(hash,16777619)}
 return(hash>>>0).toString(36);
};

export function syncKey(film:Film){
 return stableHash(JSON.stringify([film.tmdbId||film.id,Boolean(film.wished),film.rating,film.watchedDate,film.review,film.missing||[]]));
}

export function buildSyncQueue(films:Film[],completedKeys:ReadonlySet<string>=new Set()):SyncItem[]{
 return films.filter(film=>film.missing?.length&&film.match!=="complete"&&film.match!=="ignored").map(film=>{
  const key=syncKey(film);
  return{key,filmId:film.id,tmdbId:film.tmdbId,title:film.title,operation:film.wished?"add-watchlist":"upsert-log",fields:film.missing||[],status:completedKeys.has(key)?"synced":film.match==="review"||!film.tmdbId?"needs-review":"pending"};
 });
}

const csvCell=(value:string|number|null|undefined)=>{
 const text=String(value??"");
 return/[",\r\n]/.test(text)?`"${text.replace(/"/g,'""')}"`:text;
};
const csv=(headers:string[],rows:Array<Array<string|number|null|undefined>>)=>"\uFEFF"+[headers,...rows].map(row=>row.map(csvCell).join(",")).join("\r\n")+"\r\n";

export function buildOfficialImports(films:Film[]){
 const ready=films.filter(film=>film.match==="ready"&&film.missing?.length&&film.tmdbId);
 const diary=ready.filter(film=>!film.wished).map(film=>{const missing=film.missing||[],wholeFilm=missing.includes("film");return[
  film.tmdbId,film.title,film.year,
  wholeFilm||missing.includes("rating")?film.rating??"":"",
  wholeFilm||missing.includes("date")?film.watchedDate:"",
  wholeFilm||missing.includes("review")?(film.review==="present"?"":film.review):""
 ]});
 const watchlist=ready.filter(film=>film.wished).map(film=>[film.tmdbId,film.title,film.year]);
 return{
  diary:diary.length?csv(["tmdbID","Title","Year","Rating10","WatchedDate","Review"],diary):"",
  watchlist:watchlist.length?csv(["tmdbID","Title","Year"],watchlist):"",
  diaryCount:diary.length,
  watchlistCount:watchlist.length
 };
}
