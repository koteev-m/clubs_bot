/* Exact SHA-256 primitive excerpt from reviewed adapter.c; see NOTICE.md. */
/* FIPS 180-4 SHA-256, used only for exact content identity and HMAC framing. */
typedef struct  {
  uint32_t h[8];
  uint64_t n;
  unsigned used;
  unsigned char b[64];
}
sha_ctx;
static const uint32_t K[64]=  {
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,
  0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,
  0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,
  0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,
  0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,
  0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};
static uint32_t rr(uint32_t v,unsigned n)  {
  return(v>>n)|(v<<(32-n));
}
static void sha_block(sha_ctx*c,const unsigned char*b)  {
  uint32_t w[64],a,d,e,f,g,h,x,y,bb,cc;
  unsigned i;
  for(i=0;i<16;i++)w[i]=(uint32_t)b[4*i]<<24|(uint32_t)b[4*i+1]<<16|(uint32_t)b[4*i+2]<<8|b[4*i+3];
  for(i=16;i<64;i++)w[i]=w[i-16]+(rr(w[i-15],7)^rr(w[i-15],18)^(w[i-15]>>3))+w[i-7]+(rr(w[i-2],17)^rr(w[i-2],19)^(w[i-2]>>10));
  a=c->h[0];
  bb=c->h[1];
  cc=c->h[2];
  d=c->h[3];
  e=c->h[4];
  f=c->h[5];
  g=c->h[6];
  h=c->h[7];
  for(i=0;i<64;i++)  {
    x=h+(rr(e,6)^rr(e,11)^rr(e,25))+((e&f)^(~e&g))+K[i]+w[i];
    y=(rr(a,2)^rr(a,13)^rr(a,22))+((a&bb)^(a&cc)^(bb&cc));
    h=g;
    g=f;
    f=e;
    e=d+x;
    d=cc;
    cc=bb;
    bb=a;
    a=x+y;
  }
  c->h[0]+=a;
  c->h[1]+=bb;
  c->h[2]+=cc;
  c->h[3]+=d;
  c->h[4]+=e;
  c->h[5]+=f;
  c->h[6]+=g;
  c->h[7]+=h;
}
static void sha_init(sha_ctx*c)  {
  static const uint32_t init[8]=  {
    0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
  };
  memcpy(c->h,init,32);
  c->n=0;
  c->used=0;
}
static void sha_update(sha_ctx*c,const void*p,size_t n)  {
  const unsigned char*b=p;
  c->n+=n;
  while(n)  {
    size_t take=64-c->used;
    if(take>n)take=n;
    memcpy(c->b+c->used,b,take);
    c->used+=(unsigned)take;
    b+=take;
    n-=take;
    if(c->used==64)  {
      sha_block(c,c->b);
      c->used=0;
    }
  }
}
static void sha_final(sha_ctx*c,unsigned char*out)  {
  uint64_t bits=c->n*8;
  unsigned i;
  c->b[c->used++]=128;
  if(c->used>56)  {
    memset(c->b+c->used,0,64-c->used);
    sha_block(c,c->b);
    c->used=0;
  }
  memset(c->b+c->used,0,56-c->used);
  for(i=0;i<8;i++)c->b[63-i]=(unsigned char)(bits>>(8*i));
  sha_block(c,c->b);
  for(i=0;i<32;i++)out[i]=(unsigned char)(c->h[i/4]>>(24-8*(i%4)));
}
static void hex(const unsigned char*b,size_t n,char*out)  {
  static const char t[]="0123456789abcdef";
  size_t i;
  for(i=0;i<n;i++)  {
    out[2*i]=t[b[i]>>4];
    out[2*i+1]=t[b[i]&15];
  }
  out[2*n]=0;
}
static void digest(const void*b,size_t n,char out[65])  {
  sha_ctx c;
  unsigned char v[32];
  sha_init(&c);
  sha_update(&c,b,n);
  sha_final(&c,v);
  hex(v,32,out);
}
