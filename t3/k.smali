.class public final Lt3/k;
.super Lt3/a;
.source "SourceFile"


# static fields
.field public static final synthetic e:I


# virtual methods
.method public final a()Lcom/fongmi/android/tv/bean/Config;
    .registers 2

    .line 1
    invoke-static {}, Lcom/fongmi/android/tv/bean/Config;->wall()Lcom/fongmi/android/tv/bean/Config;

    .line 4
    move-result-object v0

    .line 5
    return-object v0
.end method

.method public final e()Ljava/lang/String;
    .registers 2

    .line 1
    const-string v0, "k"

    .line 3
    return-object v0
.end method

.method public final g(Lcom/fongmi/android/tv/bean/Config;)V
    .registers 9

    .line 1
    const/4 v0, 0x0

    .line 2
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    .line 5
    move-result-object v1

    .line 6
    invoke-static {v0}, LU3/f;->q(I)Ljava/io/File;

    .line 9
    move-result-object v2

    .line 10
    const-string v3, "wallpaper_cache"

    .line 12
    invoke-static {v3}, LK7/a;->s(Ljava/lang/String;)Ljava/io/File;

    .line 15
    move-result-object v4

    .line 16
    invoke-static {v4}, LK7/a;->f(Ljava/io/File;)V

    .line 19
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 22
    move-result-object p1

    .line 23
    const-string v4, "file"

    .line 25
    invoke-virtual {p1, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    .line 28
    move-result v4

    .line 29
    if-eqz v4, :cond_26

    .line 31
    invoke-static {p1}, LK7/a;->I(Ljava/lang/String;)Ljava/io/File;

    .line 34
    move-result-object p1

    .line 35
    invoke-static {p1, v2}, LK7/a;->k(Ljava/io/File;Ljava/io/File;)V

    .line 38
    goto :goto_36

    .line 39
    :cond_26
    invoke-static {p1}, LU3/f;->a(Ljava/lang/String;)Ljava/lang/String;

    .line 42
    move-result-object p1

    .line 43
    new-instance v4, Lg6/c;

    .line 45
    invoke-direct {v4, p1, v2}, Lg6/c;-><init>(Ljava/lang/String;Ljava/io/File;)V

    .line 48
    const-string p1, "k"

    .line 50
    iput-object p1, v4, Lg6/c;->p:Ljava/lang/Object;

    .line 52
    invoke-virtual {v4}, Lg6/c;->D()Ljava/io/File;

    .line 55
    :goto_36
    invoke-static {v2}, LK7/a;->r(Ljava/io/File;)Z

    .line 58
    move-result p1

    .line 59
    if-eqz p1, :cond_129

    .line 61
    const-string p1, "wall_type"

    .line 63
    invoke-static {v1, p1}, LR6/g;->Q(Ljava/lang/Object;Ljava/lang/String;)V

    .line 66
    const/4 v4, 0x1

    .line 67
    :try_start_42
    new-instance v5, Landroid/graphics/BitmapFactory$Options;

    .line 69
    invoke-direct {v5}, Landroid/graphics/BitmapFactory$Options;-><init>()V

    .line 72
    iput-boolean v4, v5, Landroid/graphics/BitmapFactory$Options;->inJustDecodeBounds:Z

    .line 74
    invoke-virtual {v2}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;

    .line 77
    move-result-object v6

    .line 78
    invoke-static {v6, v5}, Landroid/graphics/BitmapFactory;->decodeFile(Ljava/lang/String;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;

    .line 81
    iget-object v5, v5, Landroid/graphics/BitmapFactory$Options;->outMimeType:Ljava/lang/String;
    :try_end_52
    .catch Ljava/lang/Exception; {:try_start_42 .. :try_end_52} :catch_53

    .line 83
    goto :goto_54

    .line 84
    :catch_53
    const/4 v5, 0x0

    .line 85
    :goto_54
    const-string v6, "image/gif"

    .line 87
    invoke-virtual {v6, v5}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 90
    move-result v6

    .line 91
    if-eqz v6, :cond_64

    .line 93
    invoke-static {v4}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    .line 96
    move-result-object v0

    .line 97
    invoke-static {v0, p1}, LR6/g;->Q(Ljava/lang/Object;Ljava/lang/String;)V

    .line 100
    goto :goto_b5

    .line 101
    :cond_64
    if-eqz v5, :cond_72

    .line 103
    const-string v6, "image/"

    .line 105
    invoke-virtual {v5, v6}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    .line 108
    move-result v5

    .line 109
    if-eqz v5, :cond_72

    .line 111
    invoke-static {v1, p1}, LR6/g;->Q(Ljava/lang/Object;Ljava/lang/String;)V

    .line 114
    goto :goto_b5

    .line 115
    :cond_72
    :try_start_72
    new-instance v1, Landroid/media/MediaMetadataRetriever;

    .line 117
    invoke-direct {v1}, Landroid/media/MediaMetadataRetriever;-><init>()V
    :try_end_77
    .catch Ljava/lang/Exception; {:try_start_72 .. :try_end_77} :catch_ab

    .line 120
    :try_start_77
    invoke-virtual {v2}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;

    .line 123
    move-result-object v5

    .line 124
    invoke-virtual {v1, v5}, Landroid/media/MediaMetadataRetriever;->setDataSource(Ljava/lang/String;)V

    .line 127
    const/16 v5, 0xc

    .line 129
    invoke-virtual {v1, v5}, Landroid/media/MediaMetadataRetriever;->extractMetadata(I)Ljava/lang/String;

    .line 132
    move-result-object v5

    .line 133
    if-eqz v5, :cond_95

    .line 135
    const-string v6, "video/"

    .line 137
    invoke-virtual {v5, v6}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    .line 140
    move-result v5
    :try_end_8c
    .catchall {:try_start_77 .. :try_end_8c} :catchall_93

    .line 141
    if-eqz v5, :cond_95

    .line 143
    :goto_8e
    :try_start_8e
    invoke-virtual {v1}, Landroid/media/MediaMetadataRetriever;->release()V
    :try_end_91
    .catch Ljava/lang/Exception; {:try_start_8e .. :try_end_91} :catch_ab

    .line 146
    move v0, v4

    .line 147
    goto :goto_ab

    .line 148
    :catchall_93
    move-exception v4

    .line 149
    goto :goto_a2

    .line 150
    :cond_95
    :try_start_95
    const-string v4, "yes"

    .line 152
    const/16 v5, 0x11

    .line 154
    invoke-virtual {v1, v5}, Landroid/media/MediaMetadataRetriever;->extractMetadata(I)Ljava/lang/String;

    .line 157
    move-result-object v5

    .line 158
    invoke-virtual {v4, v5}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z

    .line 161
    move-result v4
    :try_end_a1
    .catchall {:try_start_95 .. :try_end_a1} :catchall_93

    .line 162
    goto :goto_8e

    .line 163
    :goto_a2
    :try_start_a2
    invoke-virtual {v1}, Landroid/media/MediaMetadataRetriever;->release()V
    :try_end_a5
    .catchall {:try_start_a2 .. :try_end_a5} :catchall_a6

    .line 166
    goto :goto_aa

    .line 167
    :catchall_a6
    move-exception v1

    .line 168
    :try_start_a7
    invoke-virtual {v4, v1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    .line 171
    :goto_aa
    throw v4
    :try_end_ab
    .catch Ljava/lang/Exception; {:try_start_a7 .. :try_end_ab} :catch_ab

    .line 172
    :catch_ab
    :goto_ab
    if-eqz v0, :cond_b5

    .line 174
    const/4 v0, 0x2

    .line 175
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    .line 178
    move-result-object v0

    .line 179
    invoke-static {v0, p1}, LR6/g;->Q(Ljava/lang/Object;Ljava/lang/String;)V

    .line 182
    :cond_b5
    :goto_b5
    sget-object p1, Lcom/fongmi/android/tv/App;->t:Lcom/fongmi/android/tv/App;

    .line 184
    invoke-static {p1}, Lcom/bumptech/glide/b;->d(Landroid/content/Context;)Lcom/bumptech/glide/p;

    .line 187
    move-result-object p1

    .line 188
    invoke-virtual {p1}, Lcom/bumptech/glide/p;->d()Lcom/bumptech/glide/m;

    .line 191
    move-result-object p1

    .line 192
    invoke-virtual {p1}, Ll3/a;->q()Ll3/a;

    .line 195
    move-result-object p1

    .line 196
    check-cast p1, Lcom/bumptech/glide/m;

    .line 198
    invoke-virtual {p1, v2}, Lcom/bumptech/glide/m;->b0(Ljava/io/File;)Lcom/bumptech/glide/m;

    .line 201
    move-result-object p1

    .line 202
    invoke-virtual {p1}, Ll3/a;->d()Ll3/a;

    .line 205
    move-result-object p1

    .line 206
    check-cast p1, Lcom/bumptech/glide/m;

    .line 208
    sget-object v0, Lcom/fongmi/android/tv/App;->t:Lcom/fongmi/android/tv/App;

    .line 210
    invoke-static {v0}, LU3/f;->k(Landroid/content/Context;)I

    .line 213
    move-result v0

    .line 214
    sget-object v1, Lcom/fongmi/android/tv/App;->t:Lcom/fongmi/android/tv/App;

    .line 216
    invoke-static {v1}, LU3/f;->i(Landroid/content/ContextWrapper;)I

    .line 219
    move-result v1

    .line 220
    invoke-virtual {p1, v0, v1}, Ll3/a;->z(II)Ll3/a;

    .line 223
    move-result-object p1

    .line 224
    check-cast p1, Lcom/bumptech/glide/m;

    .line 226
    invoke-virtual {p1}, Ll3/a;->L()Ll3/a;

    .line 229
    move-result-object p1

    .line 230
    check-cast p1, Lcom/bumptech/glide/m;

    .line 232
    sget-object v0, LV2/j;->b:LV2/j;

    .line 234
    invoke-virtual {p1, v0}, Ll3/a;->j(LV2/j;)Ll3/a;

    .line 237
    move-result-object p1

    .line 238
    check-cast p1, Lcom/bumptech/glide/m;

    .line 240
    invoke-virtual {p1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 243
    new-instance v0, Ll3/e;

    .line 245
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V

    .line 248
    sget-object v1, Lp3/f;->b:Lo5/j;

    .line 250
    invoke-virtual {p1, v0, v0, p1, v1}, Lcom/bumptech/glide/m;->Z(Lm3/f;Ll3/e;Ll3/a;Ljava/util/concurrent/Executor;)V

    .line 253
    invoke-virtual {v0}, Ll3/e;->get()Ljava/lang/Object;

    .line 256
    move-result-object p1

    .line 257
    check-cast p1, Landroid/graphics/Bitmap;

    .line 259
    :try_start_102
    new-instance v0, Ljava/io/FileOutputStream;

    .line 261
    invoke-static {v3}, LK7/a;->s(Ljava/lang/String;)Ljava/io/File;

    .line 264
    move-result-object v1

    .line 265
    invoke-direct {v0, v1}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V
    :try_end_10b
    .catchall {:try_start_102 .. :try_end_10b} :catchall_119

    .line 268
    :try_start_10b
    sget-object v1, Landroid/graphics/Bitmap$CompressFormat;->JPEG:Landroid/graphics/Bitmap$CompressFormat;

    .line 270
    const/16 v2, 0x64

    .line 272
    invoke-virtual {p1, v1, v2, v0}, Landroid/graphics/Bitmap;->compress(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z
    :try_end_112
    .catchall {:try_start_10b .. :try_end_112} :catchall_11b

    .line 275
    :try_start_112
    invoke-virtual {v0}, Ljava/io/FileOutputStream;->close()V
    :try_end_115
    .catchall {:try_start_112 .. :try_end_115} :catchall_119

    .line 278
    invoke-virtual {p1}, Landroid/graphics/Bitmap;->recycle()V

    .line 281
    return-void

    .line 282
    :catchall_119
    move-exception v0

    .line 283
    goto :goto_125

    .line 284
    :catchall_11b
    move-exception v1

    .line 285
    :try_start_11c
    invoke-virtual {v0}, Ljava/io/FileOutputStream;->close()V
    :try_end_11f
    .catchall {:try_start_11c .. :try_end_11f} :catchall_120

    .line 288
    goto :goto_124

    .line 289
    :catchall_120
    move-exception v0

    .line 290
    :try_start_121
    invoke-virtual {v1, v0}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    .line 293
    :goto_124
    throw v1
    :try_end_125
    .catchall {:try_start_121 .. :try_end_125} :catchall_119

    .line 294
    :goto_125
    invoke-virtual {p1}, Landroid/graphics/Bitmap;->recycle()V

    .line 297
    throw v0

    .line 298
    :cond_129
    new-instance p1, Ljava/io/FileNotFoundException;

    .line 300
    invoke-direct {p1}, Ljava/io/FileNotFoundException;-><init>()V

    .line 303
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
    invoke-static {}, LN6/d;->b()LN6/d;

    .line 18
    move-result-object v0

    .line 19
    new-instance v1, Lz3/d;

    .line 21
    sget-object v2, Lz3/c;->p:Lz3/c;

    .line 23
    invoke-direct {v1, v2}, Lz3/d;-><init>(Lz3/c;)V

    .line 26
    invoke-virtual {v0, v1}, LN6/d;->e(Ljava/lang/Object;)V

    .line 29
    return-void
.end method

.method public final l(Lcom/fongmi/android/tv/bean/Config;)V
    .registers 4

    .line 1
    iput-object p1, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 3
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->isEmpty()Z

    .line 6
    move-result v0

    .line 7
    if-eqz v0, :cond_9

    .line 9
    return-void

    .line 10
    :cond_9
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 13
    move-result-object p1

    .line 14
    sget-object v0, Lt3/h;->a:Lt3/i;

    .line 16
    iget-object v1, v0, Lt3/i;->f:Ljava/lang/String;

    .line 18
    invoke-static {v1}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    .line 21
    move-result v1

    .line 22
    if-eqz v1, :cond_1a

    .line 24
    const-string v0, ""

    .line 26
    goto :goto_1c

    .line 27
    :cond_1a
    iget-object v0, v0, Lt3/i;->f:Ljava/lang/String;

    .line 29
    :goto_1c
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 32
    move-result p1

    .line 33
    iput-boolean p1, p0, Lt3/a;->b:Z

    .line 35
    return-void
.end method
