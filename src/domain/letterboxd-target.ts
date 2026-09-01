import type{Film}from"./film";
import type{SyncItem}from"./sync";

export type LetterboxdTargetMode="official-import"|"official-api"|"local-session";
export type LetterboxdTargetCapabilities={readFullLibrary:boolean;writeLogs:boolean;writeWatchlist:boolean;background:boolean};

export interface LetterboxdTarget{
 readonly mode:LetterboxdTargetMode;
 readonly capabilities:LetterboxdTargetCapabilities;
 sync(items:SyncItem[],films:Film[]):Promise<{completed:string[];failed:Array<{key:string;reason:string}>}>;
}

export const targetCapabilities:Record<LetterboxdTargetMode,LetterboxdTargetCapabilities>={
 "official-import":{readFullLibrary:false,writeLogs:true,writeWatchlist:true,background:false},
 "official-api":{readFullLibrary:true,writeLogs:true,writeWatchlist:true,background:true},
 "local-session":{readFullLibrary:true,writeLogs:true,writeWatchlist:true,background:false}
};
