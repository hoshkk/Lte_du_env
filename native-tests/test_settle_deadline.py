from pathlib import Path
import subprocess,tempfile
h=Path(__file__).resolve().parents[1]/'app/src/main/cpp/settle_deadline.h'
code='''
#include <assert.h>
#include <stdio.h>
#include "settle_deadline.h"
int main(void){
 const int64_t start=1000000000LL,end=start+30000000LL,last=end-2000000LL;
 assert(spectrum_settle_deadline(start,end,last,10)==last+10000000LL);
 assert(spectrum_settle_deadline(start,end,0,10)==end+10000000LL);
 assert(spectrum_settle_deadline(start,end,start-1,10)==end+10000000LL);
 assert(spectrum_settle_deadline(start,end,end+1,10)==end+10000000LL);
 assert(spectrum_settle_deadline(start,end,last,50)==end+50000000LL);
 for(int ms=0;ms<=1000;ms++)for(int age=0;age<=30;age++) {
  int64_t w=end-age*1000000LL,d=spectrum_settle_deadline(start,end,w,ms);
  assert(d>=w+(int64_t)ms*1000000LL);
  assert(d<=end+(int64_t)ms*1000000LL);
 }
 puts("PASS: settle deadline never precedes final-write guard; invalid timing/startup fallback");
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'t.c').write_text(code)
 subprocess.run(['gcc','-I',str(h.parent),str(p/'t.c'),'-o',str(p/'t')],check=True)
 subprocess.run([str(p/'t')],check=True)
