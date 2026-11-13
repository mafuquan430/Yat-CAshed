import csv,json,statistics,math
from pathlib import Path
p=Path(__file__).resolve().parents[3]
out=p/'docs/validation/2026-10-24'
summary=[]
for seed in range(1,6):
 rows=list(csv.DictReader((out/f'seed-{seed}/algorithm_comparison_results.csv').open()))
 assert len(rows)==200,(seed,len(rows))
 pair={}
 for r in rows:
  assert 0<=float(r['LoadBalance'])<=1
  pair.setdefault(r['TestCase'],{})[r['Algorithm']]=r
 assert len(pair)==100
 improvements=[]; hits=[]; wins=0
 for v in pair.values():
  w,c=v['WFD'],v['CacheAware_v2']; a,b=float(w['Makespan']),float(c['Makespan'])
  improvements.append(100*(a-b)/a); hits.append(100*(float(c['CacheHit'])-float(w['CacheHit']))); wins+=b<a
 summary.append(dict(seed=seed,makespan_pct=statistics.mean(improvements),hit_pp=statistics.mean(hits),wins=wins))
text='# 五种子复现实验\n\n命令：每个 seed=1..5 执行 `java -cp "classes:lib/*" AlgorithmComparisonExperiment --seed=N --cases=100 --tasks=60 --no-viz`。8核默认配置，共500个配对用例。\n\n'
text+='|种子|配对 Makespan 改善均值 (%)|命中率差 (百分点)|胜出数/100|\n|---|---:|---:|---:|\n'
for r in summary:text+=f"|{r['seed']}|{r['makespan_pct']:.3f}|{r['hit_pp']:.3f}|{r['wins']}|\n"
for key in ['makespan_pct','hit_pp']:
 vals=[r[key] for r in summary]; mean=statistics.mean(vals); half=2.776*statistics.stdev(vals)/math.sqrt(5)
 text+=f'\n{key}: 种子均值 {mean:.3f}，95% Student-t 区间 [{mean-half:.3f}, {mean+half:.3f}]（独立种子为统计单位，df=4）。\n'
text+='\nCSV 命中率保留三位小数，派生统计具有舍入误差。结论仅适用于此模型与负载，不能外推到真实缓存硬件。全部200行/种子、100配对/种子及负载均衡范围检查通过。\n'
(out/'SUMMARY.md').write_text(text,encoding='utf-8')
print(text)