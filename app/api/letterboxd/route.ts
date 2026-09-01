function entities(value:string){return value.replace(/<!\[CDATA\[|\]\]>/g,"").replace(/&amp;/g,"&").replace(/&#39;|&apos;|&#x27;/g,"'").replace(/&quot;/g,'"').replace(/&ndash;/g,"–").replace(/&lt;/g,"<").replace(/&gt;/g,">")}
function text(value:string){return entities(value).replace(/<br\s*\/?>/gi,"\n").replace(/<[^>]+>/g," ").replace(/\s+/g," ").trim()}
function attr(html:string,name:string){return text(html.match(new RegExp(`${name}=["']([^"']*)["']`,"i"))?.[1]||"")}
function tag(xml:string,name:string){return entities(xml.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,"i"))?.[1]||"")}
function normalize(value:string){return text(value).normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().replace(/[^a-z0-9]/g,"")}

type LbFilm={id:string;tmdbId?:string;title:string;aliases?:string[];year:string;rating:number|null;watchedDate:string;review:string;sourceUrl:string;match:string;wished?:boolean};

function parseFilms(html:string,reviewOnly=false,wished=false){
 const blocks=[...html.matchAll(/<li[^>]*class=["'][^"']*poster-container[^"']*["'][^>]*>([\s\S]*?)<\/li>/gi)].map(match=>match[0]);
 return blocks.map((block,index)=>{const title=attr(block,"data-item-name")||attr(block,"data-film-name")||attr(block,"alt"),year=attr(block,"data-item-year")||attr(block,"data-film-release-year")||title.match(/\((\d{4})\)$/)?.[1]||"",slug=attr(block,"data-item-slug")||attr(block,"data-film-slug"),tmdbId=attr(block,"data-tmdb-id")||attr(block,"data-film-tmdb-id")||undefined,ratingClass=block.match(/\brated-(\d+)\b/)?.[1];return{id:`lb-${wished?"wish":"page"}-${slug||index}`,tmdbId,title:title.replace(/\s*\(\d{4}\)\s*$/,"").trim(),year,rating:ratingClass?Number(ratingClass):null,watchedDate:"",review:reviewOnly?"present":"",sourceUrl:slug?`https://letterboxd.com/film/${slug}/`:"",match:"complete",wished} as LbFilm}).filter(film=>film.title);
}

function parseRss(xml:string){
 const items=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].map(match=>match[1]);
 return items.map((item,index)=>{const title=text(tag(item,"letterboxd:filmTitle")),year=text(tag(item,"letterboxd:filmYear")),tmdbId=text(tag(item,"tmdb:movieId"))||undefined,rating=Number(text(tag(item,"letterboxd:memberRating"))),watchedDate=text(tag(item,"letterboxd:watchedDate")),sourceUrl=text(tag(item,"link"));const description=tag(item,"description").replace(/<p>\s*<img[\s\S]*?<\/p>/i,""),review=text(description);return{id:`lb-rss-${index}-${normalize(title)}`,tmdbId,title,year,rating:Number.isFinite(rating)&&rating>0?rating*2:null,watchedDate,review:review?"present":"",sourceUrl,match:"complete"} as LbFilm}).filter(film=>film.title);
}

async function fetchPages(username:string,path:string,reviewOnly=false,wished=false){
 const firstUrl=`https://letterboxd.com/${username}/${path}`,headers={accept:"text/html","user-agent":"Mozilla/5.0 ReviewOnce/1.1"};
 const first=await fetch(firstUrl,{headers});
 if(!first.ok)throw Error(first.status===404?"Profil Letterboxd introuvable":`Letterboxd a répondu ${first.status}`);
 const firstHtml=await first.text(),pages=Math.min(100,Math.max(1,...[...firstHtml.matchAll(/\/page\/(\d+)\//g)].map(match=>Number(match[1])))),results=parseFilms(firstHtml,reviewOnly,wished);
 for(let page=2;page<=pages;page++){const response=await fetch(`${firstUrl}page/${page}/`,{headers});if(!response.ok)break;results.push(...parseFilms(await response.text(),reviewOnly,wished))}
 return results;
}

async function fetchRss(username:string){
 const response=await fetch(`https://letterboxd.com/${username}/rss/`,{headers:{accept:"application/rss+xml, application/xml;q=0.9, text/xml;q=0.8","user-agent":"Mozilla/5.0 ReviewOnce/1.1"}});
 if(!response.ok)throw Error(response.status===404?"Profil Letterboxd introuvable":`Letterboxd a répondu ${response.status}`);
 const movies=parseRss(await response.text()),dates=movies.map(movie=>movie.watchedDate).filter(Boolean).sort();
 return{movies,coverageFrom:dates[0]||new Date(Date.now()-45*86400000).toISOString().slice(0,10)};
}

export async function GET(request:Request){
 const username=new URL(request.url).searchParams.get("username")?.trim();
 if(!username||!/^[\w-]{2,40}$/i.test(username))return Response.json({error:"Nom Letterboxd invalide"},{status:400});
 try{
  try{const [library,reviews,watchlist]=await Promise.all([fetchPages(username,"films/by/date/"),fetchPages(username,"films/reviews/",true),fetchPages(username,"watchlist/",false,true)]),reviewed=new Set(reviews.map(film=>`${normalize(film.title)}:${film.year}`)),movies=[...library.map(film=>({...film,review:reviewed.has(`${normalize(film.title)}:${film.year}`)?"present":""})),...watchlist];return Response.json({movies,total:movies.length,source:"full-profile",limited:false},{headers:{"cache-control":"public, max-age=300, s-maxage=1800"}})}
  catch(error){if(!(error instanceof Error)||!/\b403\b/.test(error.message))throw error;const fallback=await fetchRss(username);return Response.json({...fallback,total:fallback.movies.length,source:"rss",limited:true,notice:"Letterboxd bloque la lecture complète. Vérification limitée aux activités récentes."},{headers:{"cache-control":"public, max-age=300, s-maxage=900"}})}
 }catch(error){return Response.json({error:error instanceof Error?error.message:"Lecture Letterboxd impossible"},{status:502})}
}
