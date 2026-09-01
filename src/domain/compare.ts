import type{Film,MissingField}from"./film";

export const normalizeTitle=(value:string)=>value.normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().replace(/[^a-z0-9]/g,"");

const normalizedTitles=(film:Film)=>[film.title,...(film.aliases||[])].map(normalizeTitle).filter(Boolean);
const sameTitle=(left:Film,right:Film)=>{
 const rightTitles=new Set(normalizedTitles(right));
 return normalizedTitles(left).some(title=>rightTitles.has(title))&&(!left.year||!right.year||left.year===right.year);
};

export function compareLibraries(senscritique:Film[],letterboxd:Film[],previous:Film[]=[],partial=false):Film[]{
 const previousById=new Map(previous.map(film=>[film.id,film]));
 const letterboxdByTmdb=new Map(letterboxd.filter(film=>film.tmdbId).map(film=>[film.tmdbId!,film]));
 return senscritique.filter(film=>!film.wished).map(source=>{
  const old=previousById.get(source.id),film=old?.tmdbId&&!source.tmdbId?{...source,tmdbId:old.tmdbId,letterboxdUrl:old.letterboxdUrl,matchConfidence:old.matchConfidence,matchCandidates:[]}:source;
  const exact=film.tmdbId?letterboxdByTmdb.get(film.tmdbId):undefined;
  const loose=exact||letterboxd.find(candidate=>sameTitle(film,candidate));
  const missing:MissingField[]=loose?[]:["film"];
  if(loose&&film.rating!=null&&loose.rating==null)missing.push("rating");
  if(loose&&film.review.trim()&&!loose.review.trim())missing.push("review");
  const catalogUrl=film.letterboxdUrl||(film.tmdbId?`https://letterboxd.com/tmdb/${film.tmdbId}/`:undefined);
  const identified=Boolean(loose||catalogUrl);
  const match:Film["match"]=old?.match==="ignored"?"ignored":missing.length===0?"complete":partial&&missing.includes("film")&&!identified?"review":normalizeTitle(film.title).length>2?"ready":"review";
  return{...film,missing,match,letterboxdUrl:loose?.sourceUrl||catalogUrl};
 });
}
