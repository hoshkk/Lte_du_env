from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/cpp/librtlsdr/src/tuner_r82xx.c').read_text()
core=s[s.index('static void shadow_store'):s.index('static uint8_t r82xx_bitrev')]
helpers=s[s.index('static int r82xx_mux_write('):s.index('static int r82xx_set_mux(')]
prefix='''
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#define REG_SHADOW_START 5
#define NUM_REGS 27
struct config { int max_i2c_msg_len; int i2c_addr; };
struct r82xx_priv { uint8_t regs[NUM_REGS];uint32_t pll_config_valid;uint8_t buf[64];void *rtl_dev;struct config *cfg; };
static int writes=0,fail_next=0;
static int rtlsdr_i2c_write_fn(void *dev,int addr,uint8_t *buf,int n){ writes++;if(fail_next){fail_next=0;return -1;}return n; }
'''
main='''
int main(void){
 struct config cfg={8,0x34};struct r82xx_priv p={0};p.cfg=&cfg;
 assert(r82xx_mux_write(&p,0x1b,0)==0 && writes==0);
 assert(r82xx_mux_write(&p,0x1b,0x12)==0 && writes==1);
 assert(r82xx_mux_write(&p,0x1b,0x12)==0 && writes==1);
 assert(r82xx_mux_write_mask(&p,0x1b,0x10,0xf0)==0 && writes==1);
 assert(r82xx_mux_write_mask(&p,0x1b,0x20,0xf0)==0 && writes==2);
 assert(r82xx_read_cache_reg(&p,0x1b)==0x22);
 fail_next=1;assert(r82xx_mux_write(&p,0x1b,0x33)<0);
 assert(r82xx_read_cache_reg(&p,0x1b)==0x22);
 assert(r82xx_mux_write(&p,0x1b,0x33)==0 && writes==4);
 int before=writes;
 assert(r82xx_write_reg_mask(&p,0x10,0,0x0b)==0);
 assert(r82xx_write_reg_mask(&p,0x10,0,0x0b)==0);
 assert(writes==before+2); /* ordinary PLL-style writes remain unsuppressed */
 puts("PASS: unchanged mux, changed mux, masked bits, failed write recovery, uncached control writes");
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(prefix+core+helpers+main)
 subprocess.run(['gcc','-Wall','-Wextra',str(p/'test.c'),'-o',str(p/'test')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 subprocess.run([str(p/'test')],check=True)
