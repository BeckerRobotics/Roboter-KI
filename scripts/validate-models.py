from pathlib import Path
import sys, json, time, base64, zipfile, wave
root=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(root/'.validation/python'))
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors
import sherpa_onnx

assets=root/'app/src/main/assets'
out=root/'validation'
out.mkdir(exist_ok=True)
resources=root/'core/src/test/resources'
resources.mkdir(parents=True,exist_ok=True)
texts=[
 "Guten Morgen! Wann beginnt das Frühstück?",
 "Ich möchte wissen, wo meine Unterlagen sind.",
 "Grüße aus Köln: Äpfel, Öl und süßer Tee.",
 "Gedächtnistraining und Beschäftigungsangebote für ältere Menschen.",
 "Hallo 👋 – wie geht’s Ihnen heute?",
 "Cafe\u0301 und Café sind schöne Wörter.",
 " Erste Zeile.\n\nZweite\tZeile.   ",
 "Don't worry, it's fine. We'll arrive at 10:30.",
 "Version 2.5, 15.09.2026 – 37,5 °C.",
 "a\u00a0b\u2003c\n",
 "Wiederholung "*400
]
jina=Tokenizer.from_file(str(assets/'embeddings/tokenizer.json'))
jina.enable_truncation(max_length=256)
wordpiece=Tokenizer(models.WordPiece.from_file(str(assets/'vocab.txt'),unk_token='[UNK]'))
wordpiece.normalizer=normalizers.BertNormalizer(lowercase=True)
wordpiece.pre_tokenizer=pre_tokenizers.BertPreTokenizer()
wordpiece.post_processor=processors.TemplateProcessing(single='[CLS] $A [SEP]',special_tokens=[('[CLS]',101),('[SEP]',102)])
wordpiece.enable_truncation(max_length=256)
lines=[]
for kind, tokenizer in [('bpe',jina),('wordpiece',wordpiece)]:
 for text in texts:
  encoded=base64.b64encode(text.encode()).decode()
  lines.append(kind+'\t'+encoded+'\t'+','.join(map(str,tokenizer.encode(text).ids)))
(resources/'tokenizer-parity.tsv').write_text('\n'.join(lines),encoding='utf-8')
vocab=json.loads((assets/'embeddings/vocab.json').read_text(encoding='utf-8'))
(resources/'jina-vocab.tsv').write_text('\n'.join(base64.b64encode(k.encode()).decode()+'\t'+str(v) for k,v in vocab.items()),encoding='utf-8')

options=ort.SessionOptions()
options.intra_op_num_threads=2
session=ort.InferenceSession(str(assets/'embeddings/model.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
print('ONNX inputs:',[(i.name,i.shape,i.type) for i in session.get_inputs()],flush=True)
print('ONNX outputs:',[(i.name,i.shape) for i in session.get_outputs()],flush=True)
def embed(text):
 ids=np.array([jina.encode(text).ids],dtype=np.int64)
 inputs={'input_ids':ids,'attention_mask':np.ones_like(ids)}
 if any(i.name=='token_type_ids' for i in session.get_inputs()): inputs['token_type_ids']=np.zeros_like(ids)
 result=session.run(None,inputs)[0]
 vector=result[0].mean(axis=0) if result.ndim==3 else result[0]
 return vector/np.linalg.norm(vector)
documents=[
 'Das gemeinsame Frühstück beginnt um acht Uhr im Speisesaal. Kaffee und Tee stehen bereit.',
 'Das Gedächtnistraining findet dienstags um zehn Uhr im Gemeinschaftsraum statt.',
 'Im Garten gibt es eine schattige Sitzbank neben dem Rosenbeet. Der Rundweg ist barrierefrei.',
 'Besucher melden sich bitte am Empfang. Die Anmeldung ist täglich von neun bis achtzehn Uhr besetzt.'
]
questions=[
 ('Wann gibt es morgens etwas zu essen?',0),
 ('Wo frühstücken wir?',0),
 ('An welchem Wochentag ist das Gedächtnistraining?',1),
 ('Wo kann ich draußen im Schatten sitzen?',2),
 ('Bis wann kann sich Besuch anmelden?',3)
]
start=time.perf_counter()
vectors=np.array([embed(t) for t in documents])
results=[]
for question,expected in questions:
 scores=vectors@embed(question)
 actual=int(np.argmax(scores))
 results.append({'question':question,'expected':expected,'actual':actual,'correct':actual==expected,'scores':[round(float(x),4) for x in scores]})
assert all(r['correct'] for r in results),results
metrics={'onnx_runtime':ort.__version__,'embedding_model':'jinaai/jina-embeddings-v2-base-de (int8)',
 'embedding_cases':results,'embedding_seconds_pc':round(time.perf_counter()-start,2),
 'tokenizer_reference_cases':len(lines),'note':'Synthetische Funktionsprüfung auf dem PC, kein Qualitäts- oder Geschwindigkeitstest auf dem Roboter.'}

voice=root/'.validation/voice'
voice.mkdir(exist_ok=True)
prefix='assets/vits-piper-de_DE-thorsten-medium/'
with zipfile.ZipFile(root/'downloads/Thorsten-Deutsch-arm64.apk') as archive:
 for entry in archive.infolist():
  if entry.filename.startswith(prefix) and not entry.is_dir():
   destination=voice/entry.filename[len(prefix):]
   if not destination.resolve().is_relative_to(voice.resolve()): raise ValueError('Invalid archive path')
   destination.parent.mkdir(parents=True,exist_ok=True)
   if not destination.exists(): destination.write_bytes(archive.read(entry))
print('Voice assets:',[p.name for p in voice.iterdir()],flush=True)
config=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
 vits=sherpa_onnx.OfflineTtsVitsModelConfig(
  model=str(voice/'de_DE-thorsten-medium.onnx'),tokens=str(voice/'tokens.txt'),data_dir=str(voice/'espeak-ng-data')
 ),num_threads=2,debug=False,provider='cpu'
))
tts=sherpa_onnx.OfflineTts(config)
start=time.perf_counter()
audio=tts.generate('Guten Morgen. Schön, dass Sie da sind. Das Frühstück beginnt um acht Uhr im Speisesaal. Möchten Sie, dass ich die Antwort noch einmal vorlese?',sid=0,speed=0.9)
samples=np.asarray(audio.samples)
with wave.open(str(out/'Hoerprobe-Thorsten.wav'),'wb') as wav:
 wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(audio.sample_rate)
 wav.writeframes((np.clip(samples,-1,1)*32767).astype('<i2').tobytes())
metrics['voice']={'sample_rate':audio.sample_rate,'audio_seconds':round(len(samples)/audio.sample_rate,2),'generation_seconds_pc':round(time.perf_counter()-start,2)}
(out/'model-validation.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(metrics,ensure_ascii=False,indent=2),flush=True)
