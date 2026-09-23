from pathlib import Path
import json,re,subprocess,textwrap,time,os
root=Path(__file__).resolve().parent.parent
out=root/'validation'
out.mkdir(exist_ok=True)
native=(root/'app/src/main/cpp/native_llm.cpp').read_text(encoding='utf-8')
grammar=re.search(r'R"grammar\((.*?)\)grammar"',native,re.S).group(1)
grammar_path=out/'answer-format.gbnf'
grammar_path.write_text(grammar,encoding='utf-8')
kotlin=(root/'app/src/main/kotlin/de/beckerrobotics/serviceroboter/app/llm/LlamaCppLanguageModel.kt').read_text(encoding='utf-8')
system=textwrap.dedent(re.search(r'val system = """(.*?)"""',kotlin,re.S).group(1)).strip()
cases=[
 {'name':'document_answer','question':'Wann beginnt das Frühstück?','context':[{'quelle':'Tagesplan.pdf','seite':2,'text':'Das Frühstück beginnt um acht Uhr im Speisesaal.'}]},
 {'name':'document_abstention','question':'Wann kommt der Friseur?','context':[{'quelle':'Tagesplan.pdf','seite':2,'text':'Das Frühstück beginnt um acht Uhr im Speisesaal.'}]},
 {'name':'offline_general','question':'Warum wechseln die Jahreszeiten?','context':[]},
 {'name':'current_information','question':'Wie ist das Wetter heute in Berlin?','context':[]},
]
exe=root/'.validation/llama-windows/llama-completion.exe'
model=root/'downloads/Qwen3-4B-Instruct-2507-Q4_K_M.gguf'
results=[]
for case in cases:
 user=json.dumps({'frage':case['question'],'auszuege':case['context']},ensure_ascii=False)
 prompt=f'<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n'
 prompt_file=out/(case['name']+'.prompt.txt')
 prompt_file.write_text(prompt,encoding='utf-8')
 command=[str(exe),'-m',str(model),'-f',str(prompt_file),'-n','384','-c','3072','-ngl','0','-t','4',
  '--temp','0.2','--top-k','20','--top-p','0.8','--seed','42','--grammar-file',str(grammar_path),
  '--no-conversation','--no-display-prompt']
 start=time.perf_counter()
 completed=subprocess.run(command,cwd=exe.parent,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
  timeout=180,creationflags=subprocess.CREATE_NO_WINDOW)
 stdout=completed.stdout.decode('utf-8',errors='replace').strip()
 stderr=completed.stderr.decode('utf-8',errors='replace')
 (out/(case['name']+'.runtime.log')).write_text(stderr,encoding='utf-8')
 (out/(case['name']+'.output.txt')).write_text(stdout,encoding='utf-8')
 found=re.search(r'\{.*\}',stdout,re.S)
 parsed=json.loads(found.group(0)) if found else None
 results.append({'case':case['name'],'exit_code':completed.returncode,'seconds_pc':round(time.perf_counter()-start,2),'response':parsed})
 print(json.dumps(results[-1],ensure_ascii=True),flush=True)
assert all(r['exit_code']==0 and r['response'] is not None for r in results)
assert results[0]['response']['supported'] and 'acht' in results[0]['response']['answer'].lower()
assert not results[1]['response']['supported']
assert results[2]['response']['supported'] and not results[2]['response']['needs_online']
assert results[3]['response']['needs_online']
(out/'llm-validation.json').write_text(json.dumps({'cases':results,'runtime':'llama.cpp b10952, Windows CPU','note':'PC-Smoke-Test mit ChatML-Prompt und der Grammatik aus der Android-Anbindung; kein Android-End-to-End-Test.'},ensure_ascii=False,indent=2),encoding='utf-8')
print('Alle vier lokalen Sprachmodellprüfungen bestanden.',flush=True)
