export type MissingField="film"|"rating"|"review"|"date";
export type MatchStatus="ready"|"review"|"complete"|"ignored";
export type FilmCandidate={tmdbId:string;title:string;year:string;letterboxdUrl:string;confidence:number};

export type Film={
 id:string;
 tmdbId?:string;
 title:string;
 aliases?:string[];
 year:string;
 rating:number|null;
 watchedDate:string;
 review:string;
 reviewUrl?:string;
 poster?:string;
 sourceUrl?:string;
 letterboxdUrl?:string;
 matchCandidates?:FilmCandidate[];
 matchConfidence?:number;
 wished?:boolean;
 synced?:boolean;
 match?:MatchStatus;
 missing?:MissingField[];
};
