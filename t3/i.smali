.class public final Lt3/i;
.super Lt3/a;
.source "SourceFile"


# static fields
.field public static final synthetic n:I


# instance fields
.field public e:Lcom/fongmi/android/tv/bean/Site;

.field public f:Ljava/lang/String;

.field public g:Lcom/fongmi/android/tv/bean/Parse;

.field public h:Ljava/util/List;

.field public i:Ljava/util/List;

.field public j:Ljava/util/List;

.field public k:Ljava/util/ArrayList;

.field public l:Ljava/util/ArrayList;

.field public m:Ljava/util/List;


# direct methods
.method public static m()I
    .registers 1

    .line 1
    sget-object v0, Lt3/h;->a:Lt3/i;

    .line 3
    invoke-virtual {v0}, Lt3/a;->d()Lcom/fongmi/android/tv/bean/Config;

    .line 6
    move-result-object v0

    .line 7
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getId()I

    .line 10
    move-result v0

    .line 11
    return v0
.end method

.method public static s(Lcom/fongmi/android/tv/bean/Config;LU4/f;)V
    .registers 3

    .line 1
    sget-object v0, Lt3/h;->a:Lt3/i;

    .line 3
    invoke-virtual {v0}, Lt3/i;->l()V

    .line 6
    iput-object p0, v0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 8
    invoke-virtual {v0, p1}, Lt3/a;->f(LU4/f;)V

    .line 11
    return-void
.end method


# virtual methods
.method public final a()Lcom/fongmi/android/tv/bean/Config;
    .registers 2

    .line 1
    invoke-static {}, Lcom/fongmi/android/tv/bean/Config;->vod()Lcom/fongmi/android/tv/bean/Config;

    .line 4
    move-result-object v0

    .line 5
    return-object v0
.end method

.method public final e()Ljava/lang/String;
    .registers 2

    .line 1
    const-string v0, "i"

    .line 3
    return-object v0
.end method

.method public final g(Lcom/fongmi/android/tv/bean/Config;)V
    .registers 11

    .line 1
    const/16 v0, 0xa

    .line 3
    const/4 v1, 0x2

    .line 4
    const/4 v2, 0x1

    .line 5
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 8
    move-result-object v3

    .line 9
    invoke-static {v3}, LU3/f;->a(Ljava/lang/String;)Ljava/lang/String;

    .line 12
    move-result-object v3

    .line 13
    const-string v4, "i"

    .line 15
    invoke-static {v3, v4}, Ls3/a;->b(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    .line 18
    move-result-object v3

    .line 19
    invoke-static {v3}, LI7/g;->w(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 22
    move-result-object v3

    .line 23
    invoke-virtual {v3}, Lcom/google/gson/JsonElement;->getAsJsonObject()Lcom/google/gson/JsonObject;

    .line 26
    move-result-object v3

    .line 27
    const-string v4, "msg"

    .line 29
    invoke-virtual {v3, v4}, Lcom/google/gson/JsonObject;->has(Ljava/lang/String;)Z

    .line 32
    move-result v5

    .line 33
    if-nez v5, :cond_259

    .line 35
    const-string v4, "urls"

    .line 37
    invoke-virtual {v3, v4}, Lcom/google/gson/JsonObject;->has(Ljava/lang/String;)Z

    .line 40
    move-result v5

    .line 41
    const/4 v6, 0x0

    .line 42
    if-eqz v5, :cond_76

    .line 44
    invoke-virtual {v3, v4}, Lcom/google/gson/JsonObject;->getAsJsonArray(Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 47
    move-result-object v0

    .line 48
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->toString()Ljava/lang/String;

    .line 51
    move-result-object v0

    .line 52
    invoke-static {v0}, Lcom/fongmi/android/tv/bean/Depot;->arrayFrom(Ljava/lang/String;)Ljava/util/List;

    .line 55
    move-result-object v0

    .line 56
    new-instance v1, Ljava/util/ArrayList;

    .line 58
    invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V

    .line 61
    invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;

    .line 64
    move-result-object v0

    .line 65
    :goto_40
    invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z

    .line 68
    move-result v2

    .line 69
    if-eqz v2, :cond_54

    .line 71
    invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    .line 74
    move-result-object v2

    .line 75
    check-cast v2, Lcom/fongmi/android/tv/bean/Depot;

    .line 77
    invoke-static {v2, v6}, Lcom/fongmi/android/tv/bean/Config;->find(Lcom/fongmi/android/tv/bean/Depot;I)Lcom/fongmi/android/tv/bean/Config;

    .line 80
    move-result-object v2

    .line 81
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    .line 84
    goto :goto_40

    .line 85
    :cond_54
    invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z

    .line 88
    move-result v0

    .line 89
    if-nez v0, :cond_6e

    .line 91
    invoke-virtual {v1, v6}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;

    .line 94
    move-result-object v0

    .line 95
    check-cast v0, Lcom/fongmi/android/tv/bean/Config;

    .line 97
    iput-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 99
    invoke-virtual {p0, v0}, Lt3/i;->g(Lcom/fongmi/android/tv/bean/Config;)V

    .line 102
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 105
    move-result-object p1

    .line 106
    invoke-static {p1}, Lcom/fongmi/android/tv/bean/Config;->delete(Ljava/lang/String;)V

    .line 109
    goto/16 :goto_258

    .line 111
    :cond_6e
    new-instance p1, Ljava/lang/Exception;

    .line 113
    const-string v0, "Depot urls is empty"

    .line 115
    invoke-direct {p1, v0}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

    .line 118
    throw p1

    .line 119
    :cond_76
    const-string v4, "headers"

    .line 121
    invoke-static {v3, v4}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 124
    move-result-object v4

    .line 125
    invoke-static {v4}, Lcom/github/catvod/bean/d;->a(Lcom/google/gson/JsonArray;)Ljava/util/List;

    .line 128
    move-result-object v4

    .line 129
    invoke-static {}, Lb4/d;->k()Lc4/e;

    .line 132
    move-result-object v5

    .line 133
    iget-object v5, v5, Lc4/e;->i:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 135
    invoke-virtual {v5, v4}, Ljava/util/concurrent/CopyOnWriteArrayList;->addAll(Ljava/util/Collection;)Z

    .line 138
    const-string v4, "proxy"

    .line 140
    invoke-static {v3, v4}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 143
    move-result-object v4

    .line 144
    invoke-static {v4}, Lcom/github/catvod/bean/f;->a(Lcom/google/gson/JsonArray;)Ljava/util/List;

    .line 147
    move-result-object v4

    .line 148
    invoke-static {v4}, Lt3/a;->k(Ljava/util/List;)V

    .line 151
    const-string v4, "rules"

    .line 153
    invoke-static {v3, v4}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 156
    move-result-object v4

    .line 157
    invoke-static {v4}, Lcom/fongmi/android/tv/bean/Rule;->arrayFrom(Lcom/google/gson/JsonElement;)Ljava/util/List;

    .line 160
    move-result-object v4

    .line 161
    iput-object v4, p0, Lt3/i;->i:Ljava/util/List;

    .line 163
    sget-object v4, Lt3/f;->a:Li3/t;

    .line 165
    iput-boolean v2, v4, Li3/t;->n:Z

    .line 167
    const-string v4, "doh"

    .line 169
    invoke-static {v3, v4}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 172
    move-result-object v4

    .line 173
    invoke-static {v4}, Lcom/github/catvod/bean/b;->a(Lcom/google/gson/JsonArray;)Ljava/util/List;

    .line 176
    move-result-object v4

    .line 177
    iput-object v4, p0, Lt3/i;->h:Ljava/util/List;

    .line 179
    const-string v4, "flags"

    .line 181
    invoke-static {v3, v4}, LI7/g;->B(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 184
    move-result-object v4

    .line 185
    iput-object v4, p0, Lt3/i;->l:Ljava/util/ArrayList;

    .line 187
    const-string v4, "hosts"

    .line 189
    invoke-static {v3, v4}, LI7/g;->B(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 192
    move-result-object v4

    .line 193
    invoke-static {v4}, Lt3/a;->j(Ljava/util/ArrayList;)V

    .line 196
    const-string v4, "ads"

    .line 198
    invoke-static {v3, v4}, LI7/g;->B(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 201
    move-result-object v4

    .line 202
    iput-object v4, p0, Lt3/i;->k:Ljava/util/ArrayList;

    .line 204
    sget-object v4, Lt3/f;->a:Li3/t;

    .line 206
    iput-boolean v2, v4, Li3/t;->n:Z

    .line 208
    const-string v4, "lives"

    .line 210
    invoke-static {v3, v4}, LI7/g;->r(Lcom/google/gson/JsonObject;Ljava/lang/String;)Z

    .line 213
    move-result v4

    .line 214
    if-eqz v4, :cond_d8

    .line 216
    goto :goto_fc

    .line 217
    :cond_d8
    invoke-static {p1, v2}, Lcom/fongmi/android/tv/bean/Config;->find(Lcom/fongmi/android/tv/bean/Config;I)Lcom/fongmi/android/tv/bean/Config;

    .line 220
    move-result-object v4

    .line 221
    invoke-virtual {v4}, Lcom/fongmi/android/tv/bean/Config;->save()Lcom/fongmi/android/tv/bean/Config;

    .line 224
    move-result-object v4

    .line 225
    sget-object v5, Lt3/d;->a:Lt3/e;

    .line 227
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 230
    move-result-object v7

    .line 231
    invoke-virtual {v5, v7}, Lt3/a;->h(Ljava/lang/String;)Z

    .line 234
    move-result v5

    .line 235
    if-eqz v5, :cond_fc

    .line 237
    sget-object v5, Lt3/d;->a:Lt3/e;

    .line 239
    invoke-virtual {v4}, Lcom/fongmi/android/tv/bean/Config;->update()Lcom/fongmi/android/tv/bean/Config;

    .line 242
    move-result-object v4

    .line 243
    invoke-virtual {v5, v4}, Lt3/e;->l(Lcom/fongmi/android/tv/bean/Config;)V

    .line 246
    invoke-virtual {v5}, Lt3/a;->d()Lcom/fongmi/android/tv/bean/Config;

    .line 249
    move-result-object v4

    .line 250
    invoke-virtual {v5, v4, v3}, Lt3/e;->o(Lcom/fongmi/android/tv/bean/Config;Lcom/google/gson/JsonObject;)V

    .line 253
    :cond_fc
    :goto_fc
    const-string v4, "wallpaper"

    .line 255
    invoke-static {v3, v4}, LI7/g;->r(Lcom/google/gson/JsonObject;Ljava/lang/String;)Z

    .line 258
    move-result v5

    .line 259
    if-eqz v5, :cond_105

    .line 261
    goto :goto_12a

    .line 262
    :cond_105
    invoke-static {v3, v4}, LI7/g;->C(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;

    .line 265
    move-result-object v4

    .line 266
    iput-object v4, p0, Lt3/i;->f:Ljava/lang/String;

    .line 268
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getName()Ljava/lang/String;

    .line 271
    move-result-object v5

    .line 272
    invoke-static {v4, v5, v1}, Lcom/fongmi/android/tv/bean/Config;->find(Ljava/lang/String;Ljava/lang/String;I)Lcom/fongmi/android/tv/bean/Config;

    .line 275
    move-result-object v4

    .line 276
    invoke-virtual {v4}, Lcom/fongmi/android/tv/bean/Config;->save()Lcom/fongmi/android/tv/bean/Config;

    .line 279
    move-result-object v4

    .line 280
    sget-object v5, Lt3/j;->a:Lt3/k;

    .line 282
    iget-object v7, p0, Lt3/i;->f:Ljava/lang/String;

    .line 284
    invoke-virtual {v5, v7}, Lt3/a;->h(Ljava/lang/String;)Z

    .line 287
    move-result v5

    .line 288
    if-eqz v5, :cond_12a

    .line 290
    sget-object v5, Lt3/j;->a:Lt3/k;

    .line 292
    invoke-virtual {v4}, Lcom/fongmi/android/tv/bean/Config;->update()Lcom/fongmi/android/tv/bean/Config;

    .line 295
    move-result-object v4

    .line 296
    invoke-virtual {v5, v4}, Lt3/k;->l(Lcom/fongmi/android/tv/bean/Config;)V

    .line 299
    :cond_12a
    :goto_12a
    const-string v4, "spider"

    .line 301
    invoke-static {v3, v4}, LI7/g;->C(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;

    .line 304
    move-result-object v4

    .line 305
    sget-object v5, Lu3/a;->a:Lg6/c;

    .line 307
    invoke-virtual {v5}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 310
    invoke-static {v4}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    .line 313
    move-result v7

    .line 314
    if-eqz v7, :cond_13c

    .line 316
    goto :goto_14d

    .line 317
    :cond_13c
    invoke-static {v4}, Ld4/c;->g(Ljava/lang/String;)Ljava/lang/String;

    .line 320
    move-result-object v7

    .line 321
    iget-object v5, v5, Lg6/c;->n:Ljava/lang/Object;

    .line 323
    check-cast v5, LA2/a;

    .line 325
    invoke-virtual {v5, v7, v4}, LA2/a;->f(Ljava/lang/String;Ljava/lang/String;)V

    .line 328
    invoke-static {v4}, Ld4/c;->g(Ljava/lang/String;)Ljava/lang/String;

    .line 331
    move-result-object v7

    .line 332
    iput-object v7, v5, LA2/a;->r:Ljava/lang/Object;

    .line 334
    :goto_14d
    const-string v5, "sites"

    .line 336
    invoke-static {v3, v5}, LI7/g;->A(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 339
    move-result-object v5

    .line 340
    invoke-static {v5}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 343
    move-result-object v5

    .line 344
    new-instance v7, Ll7/a;

    .line 346
    const/4 v8, 0x4

    .line 347
    invoke-direct {v7, v4, v8}, Ll7/a;-><init>(Ljava/lang/String;I)V

    .line 350
    invoke-interface {v5, v7}, Lj$/util/stream/Stream;->map(Ljava/util/function/Function;)Lj$/util/stream/Stream;

    .line 353
    move-result-object v4

    .line 354
    invoke-interface {v4}, Lj$/util/stream/Stream;->distinct()Lj$/util/stream/Stream;

    .line 357
    move-result-object v4

    .line 358
    new-instance v5, LS6/b;

    .line 360
    invoke-direct {v5, v0}, LS6/b;-><init>(I)V

    .line 363
    invoke-static {v5}, Lj$/util/stream/Collectors;->toCollection(Ljava/util/function/Supplier;)Lj$/util/stream/Collector;

    .line 366
    move-result-object v5

    .line 367
    invoke-interface {v4, v5}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 370
    move-result-object v4

    .line 371
    check-cast v4, Ljava/util/List;

    .line 373
    iput-object v4, p0, Lt3/i;->j:Ljava/util/List;

    .line 375
    invoke-static {}, Lcom/fongmi/android/tv/bean/Site;->findAll()Ljava/util/List;

    .line 378
    move-result-object v4

    .line 379
    invoke-static {v4}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 382
    move-result-object v4

    .line 383
    new-instance v5, Lo7/a;

    .line 385
    const/16 v7, 0xb

    .line 387
    invoke-direct {v5, v7}, Lo7/a;-><init>(I)V

    .line 390
    invoke-static {}, Lj$/util/function/Function$-CC;->identity()Ljava/util/function/Function;

    .line 393
    move-result-object v7

    .line 394
    invoke-static {v5, v7}, Lj$/util/stream/Collectors;->toMap(Ljava/util/function/Function;Ljava/util/function/Function;)Lj$/util/stream/Collector;

    .line 397
    move-result-object v5

    .line 398
    invoke-interface {v4, v5}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 401
    move-result-object v4

    .line 402
    check-cast v4, Ljava/util/Map;

    .line 404
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 407
    move-result-object v5

    .line 408
    new-instance v7, Lt3/c;

    .line 410
    invoke-direct {v7, v4, v2}, Lt3/c;-><init>(Ljava/util/Map;I)V

    .line 413
    invoke-static {v5, v7}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 416
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 419
    move-result-object v4

    .line 420
    invoke-interface {v4}, Ljava/util/List;->isEmpty()Z

    .line 423
    move-result v4

    .line 424
    if-eqz v4, :cond_1af

    .line 426
    new-instance v2, Lcom/fongmi/android/tv/bean/Site;

    .line 428
    invoke-direct {v2}, Lcom/fongmi/android/tv/bean/Site;-><init>()V

    .line 431
    goto :goto_1d4

    .line 432
    :cond_1af
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 435
    move-result-object v4

    .line 436
    invoke-static {v4}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 439
    move-result-object v4

    .line 440
    new-instance v5, Lt3/b;

    .line 442
    invoke-direct {v5, p1, v2}, Lt3/b;-><init>(Lcom/fongmi/android/tv/bean/Config;I)V

    .line 445
    invoke-interface {v4, v5}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 448
    move-result-object v2

    .line 449
    invoke-interface {v2}, Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;

    .line 452
    move-result-object v2

    .line 453
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 456
    move-result-object v4

    .line 457
    invoke-interface {v4, v6}, Ljava/util/List;->get(I)Ljava/lang/Object;

    .line 460
    move-result-object v4

    .line 461
    check-cast v4, Lcom/fongmi/android/tv/bean/Site;

    .line 463
    invoke-virtual {v2, v4}, Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;

    .line 466
    move-result-object v2

    .line 467
    check-cast v2, Lcom/fongmi/android/tv/bean/Site;

    .line 469
    :goto_1d4
    invoke-virtual {p0, p1, v2, v6}, Lt3/i;->t(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Site;Z)V

    .line 472
    const-string v2, "parses"

    .line 474
    invoke-static {v3, v2}, LI7/g;->A(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 477
    move-result-object v2

    .line 478
    invoke-static {v2}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 481
    move-result-object v2

    .line 482
    new-instance v4, Lo7/a;

    .line 484
    const/16 v5, 0xc

    .line 486
    invoke-direct {v4, v5}, Lo7/a;-><init>(I)V

    .line 489
    invoke-interface {v2, v4}, Lj$/util/stream/Stream;->map(Ljava/util/function/Function;)Lj$/util/stream/Stream;

    .line 492
    move-result-object v2

    .line 493
    invoke-interface {v2}, Lj$/util/stream/Stream;->distinct()Lj$/util/stream/Stream;

    .line 496
    move-result-object v2

    .line 497
    new-instance v4, LS6/b;

    .line 499
    invoke-direct {v4, v0}, LS6/b;-><init>(I)V

    .line 502
    invoke-static {v4}, Lj$/util/stream/Collectors;->toCollection(Ljava/util/function/Supplier;)Lj$/util/stream/Collector;

    .line 505
    move-result-object v0

    .line 506
    invoke-interface {v2, v0}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 509
    move-result-object v0

    .line 510
    check-cast v0, Ljava/util/List;

    .line 512
    invoke-interface {v0}, Ljava/util/List;->isEmpty()Z

    .line 515
    move-result v2

    .line 516
    if-nez v2, :cond_20c

    .line 518
    invoke-static {}, Lcom/fongmi/android/tv/bean/Parse;->god()Lcom/fongmi/android/tv/bean/Parse;

    .line 521
    move-result-object v2

    .line 522
    invoke-interface {v0, v6, v2}, Ljava/util/List;->add(ILjava/lang/Object;)V

    .line 525
    :cond_20c
    iput-object v0, p0, Lt3/i;->m:Ljava/util/List;

    .line 527
    invoke-virtual {p0}, Lt3/i;->o()Ljava/util/List;

    .line 530
    move-result-object v0

    .line 531
    invoke-interface {v0}, Ljava/util/List;->isEmpty()Z

    .line 534
    move-result v0

    .line 535
    if-eqz v0, :cond_21e

    .line 537
    new-instance v0, Lcom/fongmi/android/tv/bean/Parse;

    .line 539
    invoke-direct {v0}, Lcom/fongmi/android/tv/bean/Parse;-><init>()V

    .line 542
    goto :goto_243

    .line 543
    :cond_21e
    invoke-virtual {p0}, Lt3/i;->o()Ljava/util/List;

    .line 546
    move-result-object v0

    .line 547
    invoke-static {v0}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 550
    move-result-object v0

    .line 551
    new-instance v2, Lt3/b;

    .line 553
    invoke-direct {v2, p1, v1}, Lt3/b;-><init>(Lcom/fongmi/android/tv/bean/Config;I)V

    .line 556
    invoke-interface {v0, v2}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 559
    move-result-object v0

    .line 560
    invoke-interface {v0}, Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;

    .line 563
    move-result-object v0

    .line 564
    invoke-virtual {p0}, Lt3/i;->o()Ljava/util/List;

    .line 567
    move-result-object v1

    .line 568
    invoke-interface {v1, v6}, Ljava/util/List;->get(I)Ljava/lang/Object;

    .line 571
    move-result-object v1

    .line 572
    check-cast v1, Lcom/fongmi/android/tv/bean/Parse;

    .line 574
    invoke-virtual {v0, v1}, Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;

    .line 577
    move-result-object v0

    .line 578
    check-cast v0, Lcom/fongmi/android/tv/bean/Parse;

    .line 580
    :goto_243
    invoke-virtual {p0, p1, v0, v6}, Lt3/i;->u(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Parse;Z)V

    .line 583
    const-string v0, "logo"

    .line 585
    invoke-static {v3, v0}, LI7/g;->C(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;

    .line 588
    move-result-object v0

    .line 589
    invoke-virtual {p1, v0}, Lcom/fongmi/android/tv/bean/Config;->setLogo(Ljava/lang/String;)V

    .line 592
    const-string v0, "notice"

    .line 594
    invoke-static {v3, v0}, LI7/g;->C(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;

    .line 597
    move-result-object v0

    .line 598
    invoke-virtual {p1, v0}, Lcom/fongmi/android/tv/bean/Config;->setNotice(Ljava/lang/String;)V

    .line 601
    :goto_258
    return-void

    .line 602
    :cond_259
    new-instance p1, Ljava/lang/Exception;

    .line 604
    invoke-virtual {v3, v4}, Lcom/google/gson/JsonObject;->get(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 607
    move-result-object v0

    .line 608
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->getAsString()Ljava/lang/String;

    .line 611
    move-result-object v0

    .line 612
    invoke-direct {p1, v0}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

    .line 615
    throw p1
.end method

.method public final i()V
    .registers 4

    .line 1
    invoke-static {}, LN6/d;->b()LN6/d;

    .line 4
    move-result-object v0

    .line 5
    new-instance v1, Lz3/d;

    .line 7
    sget-object v2, Lz3/c;->i:Lz3/c;

    .line 9
    invoke-direct {v1, v2}, Lz3/d;-><init>(Lz3/c;)V

    .line 12
    invoke-virtual {v0, v1}, LN6/d;->e(Ljava/lang/Object;)V

    .line 15
    invoke-static {}, Lz3/d;->D()V

    .line 18
    return-void
.end method

.method public final l()V
    .registers 7

    .line 1
    const/4 v0, 0x0

    .line 2
    iput-object v0, p0, Lt3/i;->k:Ljava/util/ArrayList;

    .line 4
    iput-object v0, p0, Lt3/i;->h:Ljava/util/List;

    .line 6
    iput-object v0, p0, Lt3/i;->e:Lcom/fongmi/android/tv/bean/Site;

    .line 8
    iput-object v0, p0, Lt3/i;->f:Ljava/lang/String;

    .line 10
    iput-object v0, p0, Lt3/i;->g:Lcom/fongmi/android/tv/bean/Parse;

    .line 12
    iput-object v0, p0, Lt3/i;->j:Ljava/util/List;

    .line 14
    iput-object v0, p0, Lt3/i;->l:Ljava/util/ArrayList;

    .line 16
    iput-object v0, p0, Lt3/i;->i:Ljava/util/List;

    .line 18
    iput-object v0, p0, Lt3/i;->m:Ljava/util/List;

    .line 20
    sget-object v0, Lu3/a;->a:Lg6/c;

    .line 22
    iget-object v1, v0, Lg6/c;->n:Ljava/lang/Object;

    .line 24
    check-cast v1, LA2/a;

    .line 26
    iget-object v2, v1, LA2/a;->p:Ljava/lang/Object;

    .line 28
    check-cast v2, Lj$/util/concurrent/ConcurrentHashMap;

    .line 30
    invoke-virtual {v2}, Lj$/util/concurrent/ConcurrentHashMap;->values()Ljava/util/Collection;

    .line 33
    move-result-object v3

    .line 34
    new-instance v4, LF3/c;

    .line 36
    const/16 v5, 0xc

    .line 38
    invoke-direct {v4, v1, v5}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 41
    invoke-static {v3, v4}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 44
    iget-object v3, v1, LA2/a;->n:Ljava/lang/Object;

    .line 46
    check-cast v3, Lj$/util/concurrent/ConcurrentHashMap;

    .line 48
    invoke-virtual {v3}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 51
    iget-object v3, v1, LA2/a;->o:Ljava/lang/Object;

    .line 53
    check-cast v3, Lj$/util/concurrent/ConcurrentHashMap;

    .line 55
    invoke-virtual {v3}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 58
    invoke-virtual {v2}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 61
    iget-object v1, v1, LA2/a;->q:Ljava/lang/Object;

    .line 63
    check-cast v1, Lj$/util/concurrent/ConcurrentHashMap;

    .line 65
    invoke-virtual {v1}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 68
    iget-object v1, v0, Lg6/c;->o:Ljava/lang/Object;

    .line 70
    check-cast v1, Lg6/c;

    .line 72
    iget-object v2, v1, Lg6/c;->n:Ljava/lang/Object;

    .line 74
    check-cast v2, Lj$/util/concurrent/ConcurrentHashMap;

    .line 76
    invoke-virtual {v2}, Lj$/util/concurrent/ConcurrentHashMap;->values()Ljava/util/Collection;

    .line 79
    move-result-object v3

    .line 80
    new-instance v4, LF3/c;

    .line 82
    const/16 v5, 0xe

    .line 84
    invoke-direct {v4, v1, v5}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 87
    invoke-static {v3, v4}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 90
    invoke-virtual {v2}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 93
    iget-object v0, v0, Lg6/c;->p:Ljava/lang/Object;

    .line 95
    check-cast v0, Landroid/support/v4/media/session/q;

    .line 97
    iget-object v1, v0, Landroid/support/v4/media/session/q;->n:Ljava/lang/Object;

    .line 99
    check-cast v1, Lj$/util/concurrent/ConcurrentHashMap;

    .line 101
    invoke-virtual {v1}, Lj$/util/concurrent/ConcurrentHashMap;->values()Ljava/util/Collection;

    .line 104
    move-result-object v2

    .line 105
    new-instance v3, LF3/c;

    .line 107
    const/16 v4, 0xd

    .line 109
    invoke-direct {v3, v0, v4}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 112
    invoke-static {v2, v3}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 115
    invoke-static {}, Lcom/fongmi/quickjs/utils/Module;->get()Lcom/fongmi/quickjs/utils/Module;

    .line 118
    move-result-object v0

    .line 119
    invoke-virtual {v0}, Lcom/fongmi/quickjs/utils/Module;->clear()V

    .line 122
    invoke-virtual {v1}, Lj$/util/concurrent/ConcurrentHashMap;->clear()V

    .line 125
    sget-object v0, Lt3/f;->a:Li3/t;

    .line 127
    const/4 v1, 0x1

    .line 128
    iput-boolean v1, v0, Li3/t;->n:Z

    .line 130
    return-void
.end method

.method public final n()Ljava/util/ArrayList;
    .registers 3

    .line 1
    sget-object v0, Lcom/fongmi/android/tv/App;->t:Lcom/fongmi/android/tv/App;

    .line 3
    invoke-static {v0}, Lcom/github/catvod/bean/b;->b(Lcom/fongmi/android/tv/App;)Ljava/util/ArrayList;

    .line 6
    move-result-object v0

    .line 7
    iget-object v1, p0, Lt3/i;->h:Ljava/util/List;

    .line 9
    if-nez v1, :cond_b

    .line 11
    return-object v0

    .line 12
    :cond_b
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->removeAll(Ljava/util/Collection;)Z

    .line 15
    iget-object v1, p0, Lt3/i;->h:Ljava/util/List;

    .line 17
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->addAll(Ljava/util/Collection;)Z

    .line 20
    return-object v0
.end method

.method public final o()Ljava/util/List;
    .registers 2

    .line 1
    iget-object v0, p0, Lt3/i;->m:Ljava/util/List;

    .line 3
    if-nez v0, :cond_8

    .line 5
    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    .line 8
    move-result-object v0

    .line 9
    :cond_8
    return-object v0
.end method

.method public final p(ILjava/lang/String;)Ljava/util/List;
    .registers 6

    .line 1
    invoke-virtual {p0}, Lt3/i;->o()Ljava/util/List;

    .line 4
    move-result-object v0

    .line 5
    invoke-static {v0}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 8
    move-result-object v0

    .line 9
    new-instance v1, Lt3/g;

    .line 11
    invoke-direct {v1, p1}, Lt3/g;-><init>(I)V

    .line 14
    invoke-interface {v0, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 17
    move-result-object p1

    .line 18
    invoke-interface {p1}, Lj$/util/stream/Stream;->toList()Ljava/util/List;

    .line 21
    move-result-object p1

    .line 22
    invoke-static {p1}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 25
    move-result-object v0

    .line 26
    new-instance v1, Lcom/fongmi/android/tv/bean/a;

    .line 28
    const/16 v2, 0x9

    .line 30
    invoke-direct {v1, p2, v2}, Lcom/fongmi/android/tv/bean/a;-><init>(Ljava/lang/String;I)V

    .line 33
    invoke-interface {v0, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 36
    move-result-object p2

    .line 37
    invoke-interface {p2}, Lj$/util/stream/Stream;->toList()Ljava/util/List;

    .line 40
    move-result-object p2

    .line 41
    invoke-interface {p2}, Ljava/util/List;->isEmpty()Z

    .line 44
    move-result v0

    .line 45
    if-eqz v0, :cond_2f

    .line 47
    goto :goto_30

    .line 48
    :cond_2f
    move-object p1, p2

    .line 49
    :goto_30
    return-object p1
.end method

.method public final q(Ljava/lang/String;)Lcom/fongmi/android/tv/bean/Site;
    .registers 5

    .line 1
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 4
    move-result-object v0

    .line 5
    invoke-static {v0}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 8
    move-result-object v0

    .line 9
    new-instance v1, Lcom/fongmi/android/tv/bean/a;

    .line 11
    const/16 v2, 0x8

    .line 13
    invoke-direct {v1, p1, v2}, Lcom/fongmi/android/tv/bean/a;-><init>(Ljava/lang/String;I)V

    .line 16
    invoke-interface {v0, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 19
    move-result-object p1

    .line 20
    invoke-interface {p1}, Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;

    .line 23
    move-result-object p1

    .line 24
    new-instance v0, Lcom/fongmi/android/tv/bean/Site;

    .line 26
    invoke-direct {v0}, Lcom/fongmi/android/tv/bean/Site;-><init>()V

    .line 29
    invoke-virtual {p1, v0}, Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;

    .line 32
    move-result-object p1

    .line 33
    check-cast p1, Lcom/fongmi/android/tv/bean/Site;

    .line 35
    return-object p1
.end method

.method public final r()Ljava/util/List;
    .registers 2

    .line 1
    iget-object v0, p0, Lt3/i;->j:Ljava/util/List;

    .line 3
    if-nez v0, :cond_8

    .line 5
    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    .line 8
    move-result-object v0

    .line 9
    :cond_8
    return-object v0
.end method

.method public final t(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Site;Z)V
    .registers 5

    .line 1
    iput-object p2, p0, Lt3/i;->e:Lcom/fongmi/android/tv/bean/Site;

    .line 3
    const/4 v0, 0x1

    .line 4
    invoke-virtual {p2, v0}, Lcom/fongmi/android/tv/bean/Site;->setActivated(Z)V

    .line 7
    iget-object p2, p0, Lt3/i;->e:Lcom/fongmi/android/tv/bean/Site;

    .line 9
    invoke-virtual {p2}, Lcom/fongmi/android/tv/bean/Site;->getKey()Ljava/lang/String;

    .line 12
    move-result-object p2

    .line 13
    invoke-virtual {p1, p2}, Lcom/fongmi/android/tv/bean/Config;->setHome(Ljava/lang/String;)V

    .line 16
    if-eqz p3, :cond_14

    .line 18
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->save()Lcom/fongmi/android/tv/bean/Config;

    .line 21
    :cond_14
    invoke-virtual {p0}, Lt3/i;->r()Ljava/util/List;

    .line 24
    move-result-object p1

    .line 25
    new-instance p2, LF3/c;

    .line 27
    const/16 p3, 0xb

    .line 29
    invoke-direct {p2, p0, p3}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 32
    invoke-static {p1, p2}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 35
    return-void
.end method

.method public final u(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Parse;Z)V
    .registers 7

    .line 1
    iput-object p2, p0, Lt3/i;->g:Lcom/fongmi/android/tv/bean/Parse;

    .line 3
    const/4 v0, 0x1

    .line 4
    invoke-virtual {p2, v0}, Lcom/fongmi/android/tv/bean/Parse;->setActivated(Z)V

    .line 7
    invoke-virtual {p2}, Lcom/fongmi/android/tv/bean/Parse;->getName()Ljava/lang/String;

    .line 10
    move-result-object v0

    .line 11
    invoke-virtual {p1, v0}, Lcom/fongmi/android/tv/bean/Config;->setParse(Ljava/lang/String;)V

    .line 14
    invoke-virtual {p0}, Lt3/i;->o()Ljava/util/List;

    .line 17
    move-result-object v0

    .line 18
    new-instance v1, LF3/c;

    .line 20
    const/16 v2, 0xa

    .line 22
    invoke-direct {v1, p2, v2}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 25
    invoke-static {v0, v1}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 28
    if-eqz p3, :cond_20

    .line 30
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->save()Lcom/fongmi/android/tv/bean/Config;

    .line 33
    :cond_20
    return-void
.end method
