from pathlib import Path
import tempfile,subprocess
s=(Path(__file__).resolve().parents[1]/'app/src/main/cpp/librtlsdr/src/librtlsdr.c').read_text()
code=s[s.index('int spectrum_set_fast_gate('):s.index('uint32_t rtlsdr_get_center_freq')]
prefix='''
#include <stdint.h>
#include <assert.h>
#include <stdio.h>
typedef struct dev rtlsdr_dev_t;
struct tuner { int (*set_freq)(rtlsdr_dev_t*,uint32_t); };
struct dev {int spectrum_fast_gate,spectrum_gate_open,direct_sampling;struct tuner*tuner;uint32_t freq,offs_freq;};
static int writes,fail_write,tunes,tune_error;
static int rtlsdr_demod_write_reg(rtlsdr_dev_t*d,int p,int a,int v,int n){writes++;if(fail_write){fail_write=0;return -4;}return 0;}
static int rtlsdr_set_if_freq(rtlsdr_dev_t*d,uint32_t f){return 0;}
static int tune(rtlsdr_dev_t*d,uint32_t f){tunes++;return tune_error;}
'''
main='''
int main(){struct tuner t={tune};rtlsdr_dev_t d={0};d.tuner=&t;
assert(!rtlsdr_set_center_freq(&d,900));assert(writes==2&&!d.spectrum_gate_open);
assert(!spectrum_set_fast_gate(&d,1));writes=0;
assert(!rtlsdr_set_center_freq(&d,901));assert(writes==1&&d.spectrum_gate_open);
assert(!rtlsdr_set_center_freq(&d,902));assert(writes==1&&tunes==3);
assert(!spectrum_set_fast_gate(&d,0));assert(writes==2&&!d.spectrum_gate_open);
assert(!rtlsdr_set_center_freq(&d,903));assert(writes==4);
spectrum_set_fast_gate(&d,1);fail_write=1;int old=tunes;
assert(rtlsdr_set_center_freq(&d,904)<0);assert(tunes==old&&!d.spectrum_gate_open&&!d.freq);
assert(!rtlsdr_set_center_freq(&d,905));tune_error=-1;
assert(rtlsdr_set_center_freq(&d,906)<0);assert(!d.spectrum_gate_open&&!d.freq);
tune_error=0;assert(!rtlsdr_set_center_freq(&d,907));assert(d.spectrum_gate_open);
puts("PASS fast gate: legacy/hold/disable/open failure/tune failure/recovery");}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'test.c').write_text(prefix+code+main)
 subprocess.run(['cc','-std=c99',str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
