const QUERY=`query UserCollection($action: ProductAction,$offset: Int,$universe: String,$username: String){user(username:$username){collection(action:$action,offset:$offset,universe:$universe){total products{id title originalTitle yearOfProduction url medias{picture} otherUserInfos(username:$username){dateDone rating isReviewed review{url}}}}}}`;
const memoryCache=new Map<string,{expires:number;movies:Film[]}>();
const matchCache=new Map<string,Resolved|null>();
const wait=(ms:number)=>new Promise(resolve=>setTimeout(resolve,ms));
const normalize=(value:string)=>value.normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().replace(/[^a-z0-9]/g,"");

function scoreCandidate(product:ScProduct,candidate:Candidate){
 const names=[product.title,product.originalTitle].filter(Boolean).map(value=>normalize(String(value))),candidateNames=[candidate.title,...candidate.aliases].map(normalize);
 const titleExact=names.some(name=>candidateNames.includes(name));
 const year=Number(product.yearOfProduction||0),candidateYear=Number(candidate.year||0),yearGap=year&&candidateYear?Math.abs(year-candidateYear):9;
 return(titleExact?65:0)+(yearGap===0?30:yearGap===1?15:0);
}

function choose(product:ScProduct,candidates:Candidate[]):Resolved|null{
 const ranked=candidates.map(candidate=>({...candidate,confidence:scoreCandidate(product,candidate)})).filter(candidate=>candidate.tmdbId).sort((a,b)=>b.confidence-a.confidence);
 if(!ranked.length)return null;
 const best=ranked[0],margin=best.confidence-(ranked[1]?.confidence||0),matchCandidates=ranked.slice(0,3).map(candidate=>({tmdbId:candidate.tmdbId,title:candidate.title,year:candidate.year,letterboxdUrl:`https://letterboxd.com/tmdb/${candidate.tmdbId}/`,confidence:candidate.confidence}));
 return best.confidence>=80&&margin>=15?{tmdbId:best.tmdbId,letterboxdUrl:`https://letterboxd.com/tmdb/${best.tmdbId}/`,confidence:best.confidence}:{matchCandidates,confidence:best.confidence};
}

async function resolveBySensCritiqueId(products:ScProduct[]){
 const recentLimit=Date.now()-180*86400000;
 const ids=[...new Set(products.filter(product=>{
  const date=product.otherUserInfos?.dateDone;
  return date&&new Date(date).getTime()>=recentLimit;
 }).map(product=>String(product.id)))];
 const missing=ids.filter(id=>!matchCache.has(id));
 for(let start=0;start<missing.length;start+=100){
  const chunk=missing.slice(start,start+100);
  chunk.forEach(id=>matchCache.set(id,null));
  const values=chunk.map(id=>JSON.stringify(id)).join(" ");
  const query=`SELECT ?sc ?tmdb ?letterboxd WHERE { VALUES ?sc { ${values} } ?item wdt:P10100 ?sc. OPTIONAL { ?item wdt:P4947 ?tmdb. } OPTIONAL { ?item wdt:P6127 ?letterboxd. } }`;
  try{
   const response=await fetch(`https://query.wikidata.org/sparql?query=${encodeURIComponent(query)}&format=json`,{headers:{accept:"application/sparql-results+json","user-agent":"ReviewOnce/1.2 (film matching)"}});
   if(!response.ok)continue;
   const data=await response.json() as WikidataResponse;
   data.results.bindings.forEach(binding=>{if(binding.tmdb?.value)matchCache.set(binding.sc.value,{tmdbId:binding.tmdb.value,letterboxdUrl:binding.letterboxd?.value?`https://letterboxd.com/film/${binding.letterboxd.value}/`:`https://letterboxd.com/tmdb/${binding.tmdb.value}/`,confidence:100})});
  }catch{/* Le titre et l'année restent le filet de sécurité. */}
 }
 return matchCache;
}

async function resolveWithTmdb(products:ScProduct[]){
 const token=process.env.TMDB_API_TOKEN;
 if(!token)return new Map<string,Resolved>();
 const resolved=new Map<string,Resolved>();
 for(const product of products.slice(0,20)){
  const candidates=new Map<string,Candidate>();
  for(const title of [...new Set([product.originalTitle,product.title].filter(Boolean))]){
   try{const params=new URLSearchParams({query:String(title),include_adult:"false",language:"fr-FR"});if(product.yearOfProduction)params.set("year",String(product.yearOfProduction));const response=await fetch(`https://api.themoviedb.org/3/search/movie?${params}`,{headers:{authorization:`Bearer ${token}`,accept:"application/json"}});if(!response.ok)continue;const data=await response.json() as TmdbResponse;data.results.slice(0,5).forEach(item=>candidates.set(String(item.id),{tmdbId:String(item.id),title:item.title||item.original_title,aliases:[item.original_title,item.title].filter(Boolean),year:item.release_date?.slice(0,4)||""}))}catch{/* Résolution manuelle si TMDB est indisponible. */}
  }
  const result=choose(product,[...candidates.values()]);if(result)resolved.set(String(product.id),result);
 }
 return resolved;
}

async function resolveWithWikidata(products:ScProduct[]){
 const resolved=new Map<string,Resolved>();
 for(const product of products.slice(0,12)){
  try{
   const candidates=new Map<string,Candidate>();
   for(const title of [...new Set([product.originalTitle,product.title].filter(Boolean))]){
    const search=await fetch(`https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${encodeURIComponent(String(title))}&language=fr&uselang=fr&type=item&limit=5&format=json&origin=*`,{headers:{"user-agent":"ReviewOnce/1.3 (film matching)"}});if(!search.ok)continue;const data=await search.json() as WikidataSearch;const ids=data.search.map(item=>item.id);if(!ids.length)continue;
    const entitiesResponse=await fetch(`https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.join("|")}&props=labels|aliases|claims&languages=fr|en|da&format=json&origin=*`,{headers:{"user-agent":"ReviewOnce/1.3 (film matching)"}});if(!entitiesResponse.ok)continue;const entities=await entitiesResponse.json() as WikidataEntities;
    Object.values(entities.entities).forEach(entity=>{const tmdb=claim(entity,"P4947"),date=claim(entity,"P577"),label=entity.labels?.fr?.value||entity.labels?.en?.value||entity.labels?.da?.value||"";if(tmdb)candidates.set(tmdb,{tmdbId:tmdb,title:label,aliases:Object.values(entity.aliases||{}).flatMap(values=>values.map(value=>value.value)),year:date.match(/\d{4}/)?.[0]||""})});
   }
   const result=choose(product,[...candidates.values()]);if(result)resolved.set(String(product.id),result);
  }catch{/* Le film restera à confirmer plutôt que d'être mal associé. */}
 }
 return resolved;
}

function claim(entity:WikidataEntity,property:string){const value=entity.claims?.[property]?.[0]?.mainsnak?.datavalue?.value;return typeof value==="object"&&value?String(value.time||""):String(value||"")}

async function requestPage(username:string,offset:number){
 const profile=`https://www.senscritique.com/${username}/collection?universe=1`;
 for(let attempt=0;attempt<3;attempt++){
  const response=await fetch("https://apollo.senscritique.com/",{method:"POST",headers:{"content-type":"application/json",accept:"application/json",referer:profile,"user-agent":"Mozilla/5.0 SensSync/1.1"},body:JSON.stringify({query:QUERY,variables:{action:"DONE",offset,universe:"movie",username}})});
  if(response.ok)return response.json() as Promise<ScResponse>;
  if(response.status!==429)throw Error(`SensCritique a répondu ${response.status}`);
  const retry=Number(response.headers.get("retry-after")||0);
  if(attempt<2)await wait(retry?Math.min(retry*1000,5000):700*(attempt+1));
 }
 throw Error("SensCritique limite temporairement les demandes. Réessaie dans quelques minutes.");
}

async function collection(username:string){
 const all:ScProduct[]=[];let total=1;
 for(let offset=0;offset<total&&offset<2000;offset+=20){
  const data=await requestPage(username,offset),result=data?.data?.user?.collection;
  if(!result)throw Error("Profil introuvable ou temporairement inaccessible");
  total=result.total;all.push(...result.products);
  if(!result.products.length)break;
  if(offset+20<total)await wait(120);
 }
 return all;
}

export async function GET(request:Request){
 const url=new URL(request.url),username=url.searchParams.get("username")?.trim(),force=url.searchParams.get("refresh")==="1";
 if(!username||!/^\w[\w-]{1,39}$/i.test(username))return Response.json({error:"Nom de profil invalide"},{status:400});
 const key=username.toLowerCase(),cached=memoryCache.get(key);
 if(!force&&cached&&cached.expires>Date.now())return Response.json({movies:cached.movies,total:cached.movies.length,source:"cache"},{headers:{"cache-control":"public, max-age=300, s-maxage=1800"}});
 try{
  const products=await collection(username);
  const direct=await resolveBySensCritiqueId(products),unresolved=products.filter(product=>!direct.get(String(product.id))&&product.otherUserInfos?.dateDone&&new Date(product.otherUserInfos.dateDone).getTime()>=Date.now()-180*86400000),tmdb=await resolveWithTmdb(unresolved),stillUnresolved=unresolved.filter(product=>!tmdb.get(String(product.id))),wikidata=await resolveWithWikidata(stillUnresolved);
  const movies=products.map((p:ScProduct)=>{const info=p.otherUserInfos||{},reviewUrl=info.isReviewed&&info.review?.url?info.review.url:"",aliases=[p.title,p.originalTitle].filter((title):title is string=>Boolean(title)),resolved=direct.get(String(p.id))||tmdb.get(String(p.id))||wikidata.get(String(p.id))||undefined;return{id:String(p.id),tmdbId:resolved?.tmdbId,title:p.title||p.originalTitle||"Film sans titre",aliases,year:String(p.yearOfProduction||""),rating:info.rating||null,watchedDate:info.dateDone?.split("T")[0]||"",review:reviewUrl?"present":"",reviewUrl,poster:p.medias?.picture||"",sourceUrl:p.url?`https://www.senscritique.com${p.url}`:"",letterboxdUrl:resolved?.letterboxdUrl,matchCandidates:resolved?.matchCandidates,matchConfidence:resolved?.confidence,synced:false,wished:false}});
  memoryCache.set(key,{expires:Date.now()+30*60*1000,movies});
  return Response.json({movies,total:movies.length,syncedAt:new Date().toISOString(),source:"senscritique"},{headers:force?{"cache-control":"no-store"}:{"cache-control":"public, max-age=300, s-maxage=1800"}});
 }catch(error){return Response.json({error:error instanceof Error?error.message:"Synchronisation impossible"},{status:503,headers:{"retry-after":"120"}})}
}
import type{Film}from"../../../src/domain/film";
type ScProduct={id:string|number;title:string;originalTitle?:string;yearOfProduction?:number;url?:string;medias?:{picture?:string};otherUserInfos?:{dateDone?:string;rating?:number;isReviewed?:boolean;review?:{url?:string}}};
type ScCollection={total:number;products:ScProduct[]};
type ScResponse={data?:{user?:{collection?:ScCollection}}};
type Resolved={tmdbId?:string;letterboxdUrl?:string;confidence:number;matchCandidates?:Film["matchCandidates"]};
type Candidate={tmdbId:string;title:string;aliases:string[];year:string};
type WikidataResponse={results:{bindings:Array<{sc:{value:string};tmdb?:{value:string};letterboxd?:{value:string}}>}};
type TmdbResponse={results:Array<{id:number;title:string;original_title:string;release_date?:string}>};
type WikidataSearch={search:Array<{id:string}>};
type WikidataEntity={labels?:Record<string,{value:string}>;aliases?:Record<string,Array<{value:string}>>;claims?:Record<string,Array<{mainsnak?:{datavalue?:{value?:string|{time?:string}}}}>>};
type WikidataEntities={entities:Record<string,WikidataEntity>};
