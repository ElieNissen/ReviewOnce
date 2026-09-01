import type{Film,MissingField}from"./film";

export const normalizeTitle=(value:string)=>value.normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().replace(/[^a-z0-9]/g,"");

const normalizedTitles=(film:Film)=>[film.title,...(film.aliases||[])].map(normalizeTitle).filter(Boolean);
const sameTitle=(left:Film,right:Film)=>{
 const rightTitles=new Set(normalizedTitles(right));
 return normalizedTitles(left).some(title=>rightTitles.has(title))&&(!left.year||!right.year||left.year===right.year);
};

export function compareLibraries(senscritique:Film[],letterboxd:Film[],previous:Film[]=[],partial=false):Film[]{
 const previousById=new Map(previous.map(film=>[film.id,film]));
 const letterboxdByTmdb=new Map(letterboxd.filter(film=>film.tmdbId).map(film=>[`${Boolean(film.wished)}:${film.tmdbId}`,film]));
 return senscritique.map(source=>{
  const old=previousById.get(source.id),film=old?.tmdbId&&!source.tmdbId?{...source,tmdbId:old.tmdbId,letterboxdUrl:old.letterboxdUrl,matchConfidence:old.matchConfidence,matchCandidates:[]}:source;
  const candidates=letterboxd.filter(candidate=>Boolean(candidate.wished)===Boolean(film.wished));
  const exact=film.tmdbId?letterboxdByTmdb.get(`${Boolean(film.wished)}:${film.tmdbId}`):undefined;
  const loose=exact||candidates.find(candidate=>sameTitle(film,candidate));
  const missing:MissingField[]=film.wished?(loose?[]:["watchlist"]):(loose?[]:["film"]);
  if(loose&&film.rating!=null&&loose.rating==null)missing.push("rating");
  if(loose&&film.review.trim()&&!loose.review.trim())missing.push("review");
  const catalogUrl=film.letterboxdUrl||(film.tmdbId?`https://letterboxd.com/tmdb/${film.tmdbId}/`:undefined);
  const identified=Boolean(loose||catalogUrl);
  const uncertainPartial=partial&&(missing.includes("film")||missing.includes("watchlist"))&&!loose;
  const match:Film["match"]=old?.match==="ignored"?"ignored":missing.length===0?"complete":uncertainPartial?"review":identified?"ready":"review";
  return{...film,missing,match,letterboxdUrl:loose?.sourceUrl||catalogUrl};
 });
}
