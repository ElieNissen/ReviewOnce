/* eslint-disable react-hooks/set-state-in-effect */
"use client";

import{useEffect,useRef,useState}from"react";
import type{Film,MissingField}from"../src/domain/film";
import{compareLibraries}from"../src/domain/compare";
import{buildOfficialImports,syncKey}from"../src/domain/sync";
import{isAndroid,letterboxdImportTarget}from"../src/platform/letterboxd-browser";

const icons:Record<string,React.ReactNode>={
 sync:<><path d="M20 7h-5V2"/><path d="M20 2l-4 4a7 7 0 1 0 2 9"/></>,
 history:<><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/></>,
 settings:<><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/></>,
 check:<path d="m5 12 4 4L19 6"/>,
 alert:<><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.7 2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z"/></>,
 external:<><path d="M15 3h6v6M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></>,
 chevron:<path d="m9 18 6-6-6-6"/>,
 close:<><path d="m6 6 12 12M18 6 6 18"/></>
};

function Icon({name,size=22}:{name:string;size?:number}){return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>{icons[name]}</svg>}
const isSelectable=(film:Film)=>Boolean(film.missing?.length&&film.tmdbId&&film.match!=="complete"&&film.match!=="ignored");
const needsChoice=(film:Film)=>Boolean(film.missing?.length&&!film.tmdbId&&film.match!=="complete"&&film.match!=="ignored");
type NativeEvent={type:string;message?:string;username?:string;connected?:boolean;collection?:Film[]};

export default function Home(){
 const[screen,setScreen]=useState<"sync"|"history"|"settings">("sync");
 const[films,setFilms]=useState<Film[]>([]),[selected,setSelected]=useState<string[]>([]);
 const[sc,setSc]=useState(""),[lb,setLb]=useState(""),[loading,setLoading]=useState(false);
 const[lastSync,setLastSync]=useState<string|null>(null),[sheet,setSheet]=useState<Film|null>(null);
 const[error,setError]=useState(""),[notice,setNotice]=useState(""),[importReady,setImportReady]=useState(false);
 const[androidApp,setAndroidApp]=useState(false),[nativeConnected,setNativeConnected]=useState(false),[hydrated,setHydrated]=useState(false);
 const nativeResolve=useRef<((films:Film[])=>void)|null>(null),nativeReject=useRef<((error:Error)=>void)|null>(null),nativeTimer=useRef<number|null>(null);

 useEffect(()=>{const schema=localStorage.getItem("reviewonce-schema");if(schema!=="2"){["senssync-films","senssync-last"].forEach(key=>localStorage.removeItem(key));localStorage.setItem("reviewonce-schema","2")}const raw=localStorage.getItem("senssync-films"),last=localStorage.getItem("senssync-last"),savedSc=localStorage.getItem("senssync-sc"),savedLb=localStorage.getItem("senssync-lb");if(raw){const saved:Film[]=JSON.parse(raw);setFilms(saved);setSelected(saved.filter(isSelectable).map(film=>film.id))}if(last)setLastSync(last);if(savedSc)setSc(savedSc);if(savedLb)setLb(savedLb);setHydrated(true)},[]);
 useEffect(()=>{const inApp=/ReviewOnceAndroid\//i.test(navigator.userAgent);setAndroidApp(inApp);if(!inApp)return;const nativeWindow=window as typeof window&{__reviewOnceNativeEvent?:(payload:string)=>void};nativeWindow.__reviewOnceNativeEvent=(payload:string)=>{try{const event=JSON.parse(payload) as NativeEvent;if(event.type==="session"){setNativeConnected(Boolean(event.connected));if(event.username){setLb(event.username);localStorage.setItem("senssync-lb",event.username)}}else if(event.type==="collection-progress"||event.type==="sync-progress"){setNotice(event.message||"Actualisation en cours…")}else if(event.type==="collection-complete"||event.type==="collection"){if(nativeTimer.current)window.clearTimeout(nativeTimer.current);nativeResolve.current?.(event.collection||[]);nativeResolve.current=null;nativeReject.current=null;if(event.message)setNotice(event.message)}else if(event.type==="collection-error"){if(nativeTimer.current)window.clearTimeout(nativeTimer.current);nativeReject.current?.(Error(event.message||"Lecture Letterboxd impossible."));nativeResolve.current=null;nativeReject.current=null}else if(event.type==="sync-error"){setError(event.message||"Synchronisation impossible.")}else if(event.type==="sync-complete"){setNotice(event.message||"Synchronisation terminée.")}}catch{setError("La réponse de l’application est illisible.")}};window.location.href="reviewonce://session";return()=>{delete nativeWindow.__reviewOnceNativeEvent;if(nativeTimer.current)window.clearTimeout(nativeTimer.current)}},[]);
 useEffect(()=>{if(films.length)localStorage.setItem("senssync-films",JSON.stringify(films))},[films]);
 useEffect(()=>{if(!hydrated)return;localStorage.setItem("senssync-sc",sc.trim());localStorage.setItem("senssync-lb",lb.trim())},[sc,lb,hydrated]);

 const selectable=films.filter(isSelectable),choice=films.filter(needsChoice),complete=films.filter(film=>film.match==="complete");
 const selectedSet=new Set(selected),selectedFilms=selectable.filter(film=>selectedSet.has(film.id));

 function refreshNativeCollection(username:string){return new Promise<Film[]>((resolve,reject)=>{nativeResolve.current=resolve;nativeReject.current=reject;nativeTimer.current=window.setTimeout(()=>{nativeResolve.current=null;nativeReject.current=null;reject(Error("L’actualisation Letterboxd prend trop de temps. Réessaie dans un instant."))},300000);window.location.href=`reviewonce://refresh?username=${encodeURIComponent(username)}`})}
 function connectLetterboxd(){setError("");setNotice("Connecte-toi une seule fois, puis ReviewOnce reviendra automatiquement.");window.location.href="reviewonce://connect"}

 async function refresh(){
  if(!sc.trim()||!lb.trim()){setError("Renseigne tes deux profils dans les réglages.");setScreen("settings");return}
  if(androidApp&&!nativeConnected){setError("Connecte ton compte Letterboxd pour continuer.");setScreen("settings");return}
  setLoading(true);setError("");setNotice("");setImportReady(false);
  try{
   const nonce=Date.now(),srPromise=fetch(`/api/senscritique?username=${encodeURIComponent(sc)}&refresh=1&_t=${nonce}`,{cache:"no-store"});
   const [sr,localMovies,lr]=androidApp?await Promise.all([srPromise,refreshNativeCollection(lb.trim()),Promise.resolve(null)]):await Promise.all([srPromise,Promise.resolve(null),fetch(`/api/letterboxd?username=${encodeURIComponent(lb)}&_t=${nonce}`,{cache:"no-store"})]);
   const sd=await sr.json(),ld=lr?await lr.json():{movies:localMovies||[],limited:false};
   if(!sr.ok)throw Error(sd.error||"SensCritique est momentanément inaccessible.");
   if(lr&&!lr.ok)throw Error(ld.error||"Letterboxd est momentanément inaccessible.");
   if(androidApp&&!ld.movies.length)throw Error("Aucun film Letterboxd n’a été trouvé pour ce profil.");
   const source:Film[]=ld.limited?sd.movies.filter((film:Film)=>film.watchedDate&&film.watchedDate>=ld.coverageFrom):sd.movies;
   const compared=compareLibraries(source,ld.movies,films,Boolean(ld.limited));
   setFilms(compared);setSelected(compared.filter(isSelectable).map(film=>film.id));
   if(ld.limited)setNotice("Décoche simplement les films que tu as déjà enregistrés sur Letterboxd.");
   const now=new Date().toISOString();setLastSync(now);localStorage.setItem("senssync-last",now);localStorage.setItem("senssync-sc",sc.trim());localStorage.setItem("senssync-lb",lb.trim());
  }catch(e){setError(e instanceof Error?e.message:"La vérification a échoué.")}finally{setLoading(false)}
 }

 function toggleFilm(id:string){setSelected(current=>current.includes(id)?current.filter(value=>value!==id):[...current,id])}
 function toggleAll(){setSelected(selectedFilms.length===selectable.length?[]:selectable.map(film=>film.id))}
 function downloadCsv(name:string,content:string){const url=URL.createObjectURL(new Blob([content],{type:"text/csv;charset=utf-8"})),link=document.createElement("a");link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}

 async function prepareOfficialImport(){
  if(!selectedFilms.length){setError("Sélectionne au moins un film à importer.");return}
  setLoading(true);setError("");setImportReady(false);
  try{
   const hydrated=[...selectedFilms];
   for(let index=0;index<hydrated.length;index++){
    const film=hydrated[index];
    if(film.missing?.includes("review")&&film.review==="present"&&film.reviewUrl){
     const response=await fetch(`/api/senscritique/review?path=${encodeURIComponent(film.reviewUrl)}`),data=await response.json();
     if(!response.ok)throw Error(data.error||`La critique de ${film.title} n’a pas pu être récupérée.`);
     hydrated[index]={...film,review:data.review||""};
    }
   }
   const files=buildOfficialImports(hydrated);
   if(!files.diaryCount&&!files.watchlistCount)throw Error("Aucun des films sélectionnés ne peut encore être importé.");
   if(files.diary)downloadCsv("reviewonce-letterboxd-films.csv",files.diary);
   if(files.watchlist)setTimeout(()=>downloadCsv("reviewonce-letterboxd-watchlist.csv",files.watchlist),250);
   setImportReady(true);
   setNotice(files.watchlistCount?"Les fichiers sont téléchargés. Ouvre maintenant Letterboxd dans ton navigateur.":"Le fichier est téléchargé. Ouvre maintenant Letterboxd dans ton navigateur.");
  }catch(e){setError(e instanceof Error?e.message:"L’import n’a pas pu être préparé.")}finally{setLoading(false)}
 }

 async function prepareAndroidSync(){
  if(!selectedFilms.length){setError("Sélectionne au moins un film à synchroniser.");return}
  setLoading(true);setError("");
  try{
   const hydrated=[...selectedFilms];
   for(let index=0;index<hydrated.length;index++){
    const film=hydrated[index];
    if(film.missing?.includes("review")&&film.review==="present"&&film.reviewUrl){
     const response=await fetch(`/api/senscritique/review?path=${encodeURIComponent(film.reviewUrl)}`),data=await response.json();
     if(!response.ok)throw Error(data.error||`La critique de ${film.title} n’a pas pu être récupérée.`);
     hydrated[index]={...film,review:data.review||""};
    }
   }
   localStorage.setItem("reviewonce-android-payload",JSON.stringify({version:1,actions:hydrated.map(film=>({key:syncKey(film),tmdbId:film.tmdbId,title:film.title,rating10:film.rating,watchedDate:film.watchedDate,review:film.review,missing:film.missing||[],watchlist:Boolean(film.wished)}))}));
   window.open("reviewonce://sync","_self");
  }catch(e){setError(e instanceof Error?e.message:"La synchronisation n’a pas pu être préparée.")}finally{setLoading(false)}
 }

 function openLetterboxdImport(){
  const target=letterboxdImportTarget(navigator.userAgent);
  if(isAndroid(navigator.userAgent))window.location.href=target;
  else window.open(target,"_blank","noopener,noreferrer");
 }

 function selectCandidate(film:Film,candidate:NonNullable<Film["matchCandidates"]>[number]){setFilms(items=>items.map(item=>item.id===film.id?{...item,tmdbId:candidate.tmdbId,letterboxdUrl:candidate.letterboxdUrl,matchCandidates:[],matchConfidence:candidate.confidence,match:"ready"}:item));setSelected(current=>current.includes(film.id)?current:[...current,film.id]);setSheet(null)}
 function ignore(film:Film){setFilms(items=>items.map(item=>item.id===film.id?{...item,match:"ignored"}:item));setSelected(current=>current.filter(id=>id!==film.id));setSheet(null)}
 function openSearch(film:Film){window.open(`https://letterboxd.com/search/films/${encodeURIComponent(film.title)}/`,"_blank","noopener,noreferrer")}
 const fieldLabel=(field:MissingField)=>({film:"Log",rating:"Note",review:"Critique",date:"Date",watchlist:"Watchlist"}[field]);
 const configured=Boolean(sc.trim()&&lb.trim()&&(!androidApp||nativeConnected));

 return <main className="app">
  <header className="appbar"><div className="appIdentity"><span className="logo">R</span><div><strong>ReviewOnce</strong><span>{lastSync?`Actualisé ${new Date(lastSync).toLocaleTimeString("fr-FR",{hour:"2-digit",minute:"2-digit"})}`:"Pas encore actualisé"}</span></div></div></header>
  <div className="content">
   {screen==="sync"&&<>
    <button className="accountStrip" onClick={()=>setScreen("settings")}><div className="account sc"><span>SC</span><div><small>SensCritique</small><strong>{sc?`@${sc}`:"À renseigner"}</strong></div>{sc&&<i/>}</div><div className="linkLine"><span/><Icon name="sync" size={16}/><span/></div><div className="account lb"><span>LB</span><div><small>Letterboxd</small><strong>{lb?`@${lb}`:"À connecter"}</strong></div>{lb&&(!androidApp||nativeConnected)&&<i/>}</div></button>
    {!configured?<section className="setupCard"><div className="setupIcon"><Icon name="settings"/></div><h1>Configure ReviewOnce</h1><p>Renseigne SensCritique et connecte Letterboxd une seule fois.</p><label><span className="service sc">SC</span><div><small>PROFIL SENSCRITIQUE</small><input value={sc} placeholder="Ton nom de profil" autoComplete="off" spellCheck={false} onChange={event=>setSc(event.target.value)}/></div></label>{androidApp?<button className={`connectAction ${nativeConnected?"connected":""}`} onClick={connectLetterboxd}><span className="service lb">LB</span><div><strong>{nativeConnected&&lb?`@${lb}`:"Connecter Letterboxd"}</strong><small>{nativeConnected?"Compte connecté sur cet appareil":"La connexion restera enregistrée"}</small></div>{nativeConnected?<Icon name="check"/>:<Icon name="chevron"/>}</button>:<label><span className="service lb">LB</span><div><small>PROFIL LETTERBOXD</small><input value={lb} placeholder="Ton nom de profil" autoComplete="off" spellCheck={false} onChange={event=>setLb(event.target.value)}/></div></label>}{error&&<div className="syncError" role="alert"><Icon name="alert" size={17}/><span>{error}</span></div>}{notice&&<div className="syncNotice"><Icon name="alert" size={17}/><span>{notice}</span></div>}</section>:<>
    <section className="syncSummary"><div><span className="bigCount">{selectable.length}</span><div><h1>film{selectable.length>1?"s":""} à importer</h1><p>{selectable.length?"Choisis ceux à ajouter à Letterboxd.":"Actualise pour chercher les nouveaux films."}</p></div></div><button onClick={refresh} disabled={loading}><Icon name="sync" size={18}/>{loading?"Actualisation…":"Actualiser"}</button>{error&&<div className="syncError" role="alert"><Icon name="alert" size={17}/><span><strong>Un problème est survenu</strong>{error}</span></div>}{notice&&<div className="syncNotice"><Icon name="alert" size={17}/><span>{notice}</span></div>}{importReady&&<div className="importReady"><div><strong>Fichier prêt</strong><span>Le téléchargement est terminé.</span></div><button onClick={openLetterboxdImport}><Icon name="external" size={17}/>Ouvrir dans le navigateur</button></div>}</section>
    {selectable.length>0&&<div className="selectionBar"><button onClick={toggleAll}>{selectedFilms.length===selectable.length?"Tout désélectionner":"Tout sélectionner"}</button><span>{selectedFilms.length} sur {selectable.length}</span></div>}
    <section className="list">
     {!films.length?<div className="emptyState"><div><Icon name="sync" size={28}/></div><h2>Trouve les films à importer</h2><p>ReviewOnce compare tes deux profils et prépare uniquement ce qu’il reste à ajouter.</p><button onClick={refresh}>Comparer mes profils</button></div>:!selectable.length&&!choice.length?<div className="emptyState compact"><div><Icon name="check" size={28}/></div><h2>Tout est à jour</h2><p>Aucun nouveau film à importer pour le moment.</p></div>:selectable.map(film=><article className={`film selectableFilm ${selectedSet.has(film.id)?"selected":""}`} key={film.id}><label className="filmCheckbox" aria-label={`Sélectionner ${film.title}`}><input type="checkbox" checked={selectedSet.has(film.id)} onChange={()=>toggleFilm(film.id)}/><span><Icon name="check" size={15}/></span></label><div className="poster">{film.poster?<img src={film.poster} alt=""/>:<span>{film.title[0]}</span>}</div><div className="filmMain"><h2>{film.title}</h2><p>{film.year}{film.watchedDate&&` · ${new Date(film.watchedDate+"T12:00").toLocaleDateString("fr-FR",{day:"numeric",month:"short"})}`}</p><div className="missingFields">{film.missing?.map(field=><span key={field}>{fieldLabel(field)}</span>)}</div></div></article>)}
     {choice.length>0&&<><div className="listSectionTitle"><strong>{choice.length} film{choice.length>1?"s":""} à choisir</strong><span>Indique le bon film avant de l’importer.</span></div>{choice.map(film=><article className="film" key={film.id}><div className="poster">{film.poster?<img src={film.poster} alt=""/>:<span>{film.title[0]}</span>}</div><div className="filmMain"><h2>{film.title}</h2><p>{film.year}</p><span className="match review">Choisir le film</span></div><button className="more" onClick={()=>setSheet(film)} aria-label={`Choisir ${film.title}`}><Icon name="chevron"/></button></article>)}</>}
    </section>
    {selectedFilms.length>0&&<div className="actionDock"><div><strong>{selectedFilms.length} film{selectedFilms.length>1?"s":""}</strong><span>Notes, dates et critiques incluses</span></div><button onClick={androidApp?prepareAndroidSync:prepareOfficialImport} disabled={loading}>{loading?"Préparation…":androidApp?`Synchroniser ${selectedFilms.length}`:`Importer ${selectedFilms.length}`} <Icon name="chevron" size={17}/></button></div>}
    </>}
   </>}
   {screen==="history"&&<section className="screen"><div className="titleBlock"><h1>Déjà sur Letterboxd</h1><p>Les films pour lesquels il ne reste rien à ajouter.</p></div><div className="historyCard"><div className="historyIcon"><Icon name="check"/></div><div><strong>{complete.length} film{complete.length>1?"s":""} à jour</strong><span>Aucune action nécessaire</span></div></div>{complete.map(film=><article className="historyRow" key={film.id}><span>{film.title[0]}</span><div><strong>{film.title}</strong><small>{film.year}</small></div><Icon name="check" size={18}/></article>)}</section>}
   {screen==="settings"&&<section className="screen"><div className="titleBlock"><h1>Réglages</h1><p>Tes profils et ta session restent uniquement sur cet appareil.</p></div><h3 className="sectionLabel">PROFILS</h3><div className="settingsGroup"><label><span className="service sc">SC</span><div><small>SensCritique</small><input value={sc} placeholder="Nom de profil" autoComplete="off" spellCheck={false} onChange={event=>setSc(event.target.value)}/></div>{sc&&<i className="online"/>}</label>{androidApp?<button onClick={connectLetterboxd}><span className="service lb">LB</span><div><small>Letterboxd</small><strong>{nativeConnected&&lb?`@${lb}`:"Connecter mon compte"}</strong></div>{nativeConnected?<span className="pill connectedPill">Connecté</span>:<Icon name="chevron"/>}</button>:<label><span className="service lb">LB</span><div><small>Letterboxd</small><input value={lb} placeholder="Nom de profil" autoComplete="off" spellCheck={false} onChange={event=>setLb(event.target.value)}/></div>{lb&&<i className="online"/>}</label>}</div>{error&&<div className="syncError" role="alert"><Icon name="alert" size={17}/><span>{error}</span></div>}<h3 className="sectionLabel">SYNCHRONISATION</h3><div className="settingsGroup"><button className="disabled"><Icon name="sync"/><div><strong>{androidApp?"Synchronisation directe":"Import groupé"}</strong><small>{androidApp?"Depuis cette application, sans fichier CSV":"Un seul fichier pour les films sélectionnés"}</small></div><span className="pill">Actuel</span></button></div></section>}
  </div>
  <nav className="bottomNav" aria-label="Navigation principale"><button aria-current={screen==="sync"} onClick={()=>setScreen("sync")}><span><Icon name="sync"/>{selectable.length>0&&<i>{selectable.length}</i>}</span>À importer</button><button aria-current={screen==="history"} onClick={()=>setScreen("history")}><Icon name="history"/>Déjà ajoutés</button><button aria-current={screen==="settings"} onClick={()=>setScreen("settings")}><Icon name="settings"/>Réglages</button></nav>
  {sheet&&<div className="scrim" onMouseDown={event=>event.currentTarget===event.target&&setSheet(null)}><section className="sheet"><div className="grab"/><button className="sheetClose" onClick={()=>setSheet(null)} aria-label="Fermer"><Icon name="close"/></button><div className="sheetFilm"><div className="poster large">{sheet.poster?<img src={sheet.poster} alt=""/>:sheet.title[0]}</div><div><h2>{sheet.title}</h2><p>{sheet.year}</p></div></div>{sheet.matchCandidates?.length?<div className="candidateList"><small>CHOISIS LE BON FILM</small>{sheet.matchCandidates.map(candidate=><button key={candidate.tmdbId} onClick={()=>selectCandidate(sheet,candidate)}><span><strong>{candidate.title}</strong><em>{candidate.year||"Année inconnue"}</em></span><span><Icon name="chevron" size={15}/></span></button>)}</div>:<><p className="helper">ReviewOnce n’a pas trouvé de correspondance certaine.</p><button className="mainAction" onClick={()=>openSearch(sheet)}><Icon name="external"/>Rechercher sur Letterboxd</button></>}<button className="textAction" onClick={()=>ignore(sheet)}>Ne pas importer ce film</button></section></div>}
 </main>
}
