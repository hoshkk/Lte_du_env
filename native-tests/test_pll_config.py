"""Compare actual PLL programming against the uncached sequence with mocked I2C.
This checks register/transaction logic, not RF accuracy or hardware timing.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
s = (root / 'app/src/main/cpp/librtlsdr/src/tuner_r82xx.c').read_text()
core = s[s.index('static void shadow_store'):s.index('static uint8_t r82xx_bitrev')]
helper = s[s.index('static int r82xx_pll_config_write_mask'):s.index('static int r82xx_set_pll')]
pll = s[s.index('static int r82xx_set_pll'):s.index('static int r82xx_sysfreq_sel')]
reference = pll.replace('r82xx_set_pll(', 'reference_pll(').replace('r82xx_pll_config_write_mask(', 'r82xx_write_reg_mask(')
prefix = r'''
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <unistd.h>
#define REG_SHADOW_START 5
#define NUM_REGS 27
#define CHIP_R828D 1
struct config { int max_i2c_msg_len; int i2c_addr; uint32_t xtal; int rafael_chip; };
struct r82xx_priv { uint8_t regs[NUM_REGS]; uint32_t pll_config_valid; uint8_t buf[64]; void *rtl_dev; struct config *cfg; int has_lock; int init_done; };
static int writes, fail_next, reads, lock_reads, lock_after=1, fine=1;
static int rtlsdr_i2c_write_fn(void *d,int addr,uint8_t *b,int n) {
 writes++; if(fail_next) { int e=fail_next;fail_next=0;return e; } return n;
}
static int rtlsdr_check_dongle_model(void *d,const char *a,const char *b) { return 0; }
static int r82xx_read(struct r82xx_priv *p,uint8_t reg,uint8_t *b,int n) {
 reads++;memset(b,0,n);
 if(n==5)b[4]=fine<<4;
 if(n==3){lock_reads++;if(lock_reads>=lock_after)b[2]=0x40;}
 return 0;
}
static void counters(void){writes=reads=lock_reads=0;}
'''
main = r'''
int main(void) {
 struct config cfg={8,0x34,28800000,CHIP_R828D};
 struct r82xx_priv a={0},b={0};a.cfg=b.cfg=&cfg; a.init_done=b.init_done=1;
 /* Unknown shadow cannot suppress even an identical divider value. */
 assert(r82xx_pll_config_write_mask(&a,0x10,0,0x10)==0 && writes==1);
 assert(a.pll_config_valid);
 assert(r82xx_pll_config_write_mask(&a,0x10,0,0x10)==0 && writes==1);
 /* Other registers and masks are never eligible. */
 assert(r82xx_pll_config_write_mask(&a,0x12,0,0xe0)==0 && writes==2);
 assert(r82xx_pll_config_write_mask(&a,0x10,0,0x0b)==0 && writes==3);
 fail_next=-4;
 assert(r82xx_pll_config_write_mask(&a,0x10,0x20,0xe0)==-4);
 assert(!a.pll_config_valid && a.regs[0x10-5]==0);
 assert(r82xx_pll_config_write_mask(&a,0x10,0,0x10)==0);
 assert(a.pll_config_valid && writes==5);

 /* Initialization/calibration must not use the new cached fields. */
 a.init_done=0;int before=writes;
 assert(r82xx_pll_config_write_mask(&a,0x14,0,0xff)==0);
 assert(r82xx_pll_config_write_mask(&a,0x14,0,0xff)==0);
 assert(writes==before+2);a.init_done=1;
 /* Per-register validity: writing 0x10 cannot validate unknown 0x14. */
 a.pll_config_valid=0;
 assert(r82xx_write_reg(&a,0x10,a.regs[0x10-5])==0);before=writes;
 assert(r82xx_pll_config_write_mask(&a,0x14,0,0xff)==0 && writes==before+1);
 assert(r82xx_pll_config_write_mask(&a,0x14,0,0xff)==0 && writes==before+1);
 /* Full sweep and divider boundary transitions preserve final registers
    and both fine-tune and PLL-lock status reads. */
 uint32_t freq[]={905370000,907170000,908970000,910770000,912570000,
 914370000,916170000,917970000,919770000,1748570000,1750370000,
 1752170000,880000000,890000000,440000000,450000000,864000000,864000001,1728000000};
 for(int f=0;f<3;f++)for(int repeat=0;repeat<5;repeat++)
 for(unsigned i=0;i<sizeof(freq)/sizeof(freq[0]);i++) {
  fine=f;lock_after=repeat%2+1;
  counters();int ra=reference_pll(&b,freq[i]);int nw=writes,nr=reads,nl=lock_reads;
  counters();int rb=r82xx_set_pll(&a,freq[i]);
  assert(ra==rb && a.has_lock==b.has_lock);
  assert(memcmp(a.regs,b.regs,NUM_REGS)==0);
  assert(reads==nr && lock_reads==nl && writes<=nw && nw-writes<=4);
 }
 /* Steady B8 nine-segment sweep should remove exactly 36 config writes. */
 fine=1;lock_after=1;reference_pll(&b,freq[0]);r82xx_set_pll(&a,freq[0]);
 int saved=0;
 for(int i=0;i<9;i++) {
  counters();assert(reference_pll(&b,freq[i])==0);int nw=writes;
  counters();assert(r82xx_set_pll(&a,freq[i])==0);saved+=nw-writes;
  assert(reads==2 && lock_reads==1 && memcmp(a.regs,b.regs,NUM_REGS)==0);
 }
 assert(saved==36);
 /* An unlocked PLL must still reject measurement after the second read. */
 lock_after=3;counters();assert(r82xx_set_pll(&a,freq[0])<0);
 assert(lock_reads==2 && a.has_lock==0);
 puts("PASS: 285 PLL sequences, divider transitions, unknown/failed shadow, lock retry/failure; 36 fewer writes per steady B8 sweep (mock only)");
}
'''
with tempfile.TemporaryDirectory() as d:
    p=Path(d); (p/'test.c').write_text(prefix+core+helper+reference+pll+main)
    subprocess.run(['gcc','-Wall',str(p/'test.c'),'-o',str(p/'test')],check=True)
    subprocess.run([str(p/'test')],check=True)
