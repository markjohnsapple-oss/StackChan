import React, {useCallback, useEffect, useRef} from 'react';
import {StatusBar, StyleSheet, View} from 'react-native';
import WebView, {WebViewMessageEvent} from 'react-native-webview';
import * as RNFS from '@dr.pogodin/react-native-fs';
import {initLlama, LlamaContext} from 'llama.rn';
import {AURA_HTML} from './auraHtml.generated';

// Native llama.cpp CPU-first test: no WebGPU, cloud services or TTS.
const MODEL_DIR = RNFS.DocumentDirectoryPath + '/models';
const MODEL_PATH = MODEL_DIR + '/qwen3.5-4b-Q4_K_M.gguf';
const MODEL_URL = 'https://huggingface.co/gguf/Qwen3.5-4B-GGUF/resolve/main/qwen3.5-4b-Q4_K_M.gguf?download=true';
const MIN_MODEL_SIZE = 2_500_000_000;

type Turn = {role: 'user' | 'assistant'; content: string};
type Request = {type: 'hello' | 'load' | 'send'; messages?: Turn[]};
type Event = {type: string; [key: string]: unknown};

export default function App(): React.JSX.Element {
  const web = useRef<WebView>(null);
  const model = useRef<LlamaContext | null>(null);
  const loading = useRef(false);
  const generating = useRef(false);
  const mounted = useRef(true);

  const emit = useCallback((data: Event) => {
    if (!mounted.current) return;
    web.current?.injectJavaScript('window.AuraNative && window.AuraNative.receive(' + JSON.stringify(data) + '); true;');
  }, []);

  const isDownloaded = useCallback(async (): Promise<boolean> => {
    if (!(await RNFS.exists(MODEL_PATH))) return false;
    return Number((await RNFS.stat(MODEL_PATH)).size) >= MIN_MODEL_SIZE;
  }, []);

  const reportReady = useCallback(async () => {
    try {emit({type:'ready',modelFound:await isDownloaded()});}
    catch(e) {emit({type:'error',message:String(e)});}
  }, [emit, isDownloaded]);

  const download = useCallback(async () => {
    await RNFS.mkdir(MODEL_DIR);
    if (await RNFS.exists(MODEL_PATH)) await RNFS.unlink(MODEL_PATH);
    const {promise} = RNFS.downloadFile({
      fromUrl: MODEL_URL,
      toFile: MODEL_PATH,
      background: true,
      connectionTimeout: 30000,
      readTimeout: 120000,
      progressInterval: 350,
      progress: p => {
        const bytes = Number(p.bytesWritten)||0;
        const total = Number(p.contentLength)||0;
        emit({
          type:'progress',
          progress:total>0?bytes/total:0,
          message:total>0
            ?(bytes/1e9).toFixed(2)+' / '+(total/1e9).toFixed(2)+' GB · Qwen'
            :(bytes/1e9).toFixed(2)+' GB · Qwen',
        });
      },
    });
    const result = await promise;
    if (result.statusCode !== 200 || !(await isDownloaded())) {
      throw new Error('Qwen download was interrupted or incomplete (HTTP '+result.statusCode+').');
    }
  }, [emit, isDownloaded]);

  const load = useCallback(async () => {
    if (model.current) {emit({type:'loaded'});return;}
    if (loading.current) return;
    loading.current = true;
    try {
      if (!(await isDownloaded())) {
        emit({type:'progress',progress:0,message:'Downloading Qwen3.5 4B (~2.8 GB)…'});
        await download();
      }
      emit({type:'loading',message:'Starting native llama.cpp…'});
      model.current = await initLlama({
        model: MODEL_PATH,
        n_ctx: 2048,
        n_batch: 256,
        n_threads: 4,
        n_gpu_layers: 0,
        use_mmap: true,
        use_mlock: false,
      });
      emit({type:'loaded'});
    } catch(e) {
      emit({type:'error',message:e instanceof Error?e.message:String(e)});
    } finally {loading.current=false;}
  }, [download, emit, isDownloaded]);

  const send = useCallback(async (messages: Turn[]) => {
    if (generating.current) return;
    if (!model.current) {emit({type:'error',message:'Load the brain first.'});return;}
    generating.current = true;
    let streamed = '';
    try {
      const turns = messages.slice(-12).filter(m =>
        (m.role === 'user'||m.role === 'assistant') && typeof m.content === 'string' && m.content.trim().length>0);
      if (!turns.length || turns[turns.length-1].role !== 'user') {
        throw new Error('No user message received.');
      }
      const result = await model.current.completion({
        messages:[{role:'system',content:'You are AURA AHL AI. Be friendly, conversational and answer directly.'},...turns],
        n_predict: 400,
        temperature: 0.7,
        top_p: 0.9,
        chat_template_kwargs:{enable_thinking:false},
      }, data => {
        if (data.token) {
          streamed += data.token;
          emit({type:'token',text:data.token});
        }
      });
      emit({type:'complete',text:result.text || streamed});
    } catch(e) {
      emit({type:'error',message:e instanceof Error?e.message:String(e)});
    } finally {generating.current=false;}
  }, [emit]);

  const onMessage = useCallback((event:WebViewMessageEvent) => {
    try {
      const msg:Request = JSON.parse(event.nativeEvent.data);
      if (msg.type === 'hello') {void reportReady();}
      else if (msg.type === 'load') {void load();}
      else if (msg.type === 'send' && Array.isArray(msg.messages)) {void send(msg.messages);}
    } catch(e) {emit({type:'error',message:'Invalid bridge message: '+String(e)});}
  }, [emit, load, reportReady, send]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      model.current?.release().catch(()=>{});
      model.current = null;
    };
  }, []);

  return <View style={styles.root}>
    <StatusBar barStyle="light-content" backgroundColor="#080d16"/>
    <WebView
      ref={web}
      style={styles.web}
      source={{html:AURA_HTML,baseUrl:'https://appassets.androidplatform.net/'}}
      originWhitelist={['*']}
      javaScriptEnabled={true}
      domStorageEnabled={true}
      allowFileAccess={false}
      mixedContentMode="never"
      onMessage={onMessage}
      onLoadEnd={()=>{void reportReady();}}
    />
  </View>;
}

const styles=StyleSheet.create({
  root:{flex:1,backgroundColor:'#080d16'},
  web:{flex:1,backgroundColor:'#080d16'},
});
