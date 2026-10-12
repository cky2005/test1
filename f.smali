.class public LU4/f;
.super Ljava/lang/Object;
.source "SourceFile"

# interfaces
.implements Lokhttp3/Callback;
.implements LD1/g;
.implements LF0/p;
.implements LG1/k;
.implements LJ0/w;
.implements LM0/d;
.implements LN6/g;
.implements LT2/f;
.implements LV1/d;
.implements LW2/a;
.implements Lq3/a;
.implements LY0/H;


# static fields
.field public static volatile i:LU4/f;


# direct methods
.method public static F(LL4/J;J)[B
    .registers 6

    .line 1
    new-instance v0, LG0/k;

    .line 3
    const/4 v1, 0x2

    .line 4
    invoke-direct {v0, v1}, LG0/k;-><init>(I)V

    .line 7
    new-instance v1, Ljava/util/ArrayList;

    .line 9
    invoke-interface {p0}, Ljava/util/Collection;->size()I

    .line 12
    move-result v2

    .line 13
    invoke-direct {v1, v2}, Ljava/util/ArrayList;-><init>(I)V

    .line 16
    invoke-interface {p0}, Ljava/util/Collection;->iterator()Ljava/util/Iterator;

    .line 19
    move-result-object p0

    .line 20
    :goto_13
    invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z

    .line 23
    move-result v2

    .line 24
    if-eqz v2, :cond_27

    .line 26
    invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    .line 29
    move-result-object v2

    .line 30
    invoke-virtual {v0, v2}, LG0/k;->apply(Ljava/lang/Object;)Ljava/lang/Object;

    .line 33
    move-result-object v2

    .line 34
    check-cast v2, Landroid/os/Bundle;

    .line 36
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    .line 39
    goto :goto_13

    .line 40
    :cond_27
    new-instance p0, Landroid/os/Bundle;

    .line 42
    invoke-direct {p0}, Landroid/os/Bundle;-><init>()V

    .line 45
    const-string v0, "c"

    .line 47
    invoke-virtual {p0, v0, v1}, Landroid/os/Bundle;->putParcelableArrayList(Ljava/lang/String;Ljava/util/ArrayList;)V

    .line 50
    const-string v0, "d"

    .line 52
    invoke-virtual {p0, v0, p1, p2}, Landroid/os/BaseBundle;->putLong(Ljava/lang/String;J)V

    .line 55
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;

    .line 58
    move-result-object p1

    .line 59
    invoke-virtual {p1, p0}, Landroid/os/Parcel;->writeBundle(Landroid/os/Bundle;)V

    .line 62
    invoke-virtual {p1}, Landroid/os/Parcel;->marshall()[B

    .line 65
    move-result-object p0

    .line 66
    invoke-virtual {p1}, Landroid/os/Parcel;->recycle()V

    .line 69
    return-object p0
.end method

.method public static I(LY4/c;Ljava/util/Map;Ljava/net/HttpURLConnection;Z)J
    .registers 9

    .line 1
    const/4 v0, 0x1

    .line 2
    const-wide/16 v1, -0x1

    .line 4
    if-eqz p3, :cond_17

    .line 6
    :try_start_5
    iget-object p3, p0, LY4/c;->u:Ljava/lang/String;

    .line 8
    sget-object v3, La5/b;->b:LK2/a;

    .line 10
    invoke-virtual {v3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 13
    invoke-static {p3, p1, v0}, La5/b;->g(Ljava/lang/String;Ljava/util/Map;Z)Ljava/net/HttpURLConnection;

    .line 16
    move-result-object p2
    :try_end_10
    .catch Ljava/lang/Exception; {:try_start_5 .. :try_end_10} :catch_13

    .line 17
    if-nez p2, :cond_17

    .line 19
    return-wide v1

    .line 20
    :catch_13
    invoke-static {p2}, La5/b;->c(Ljava/net/HttpURLConnection;)V

    .line 23
    return-wide v1

    .line 24
    :cond_17
    const-string p3, "content-length"

    .line 26
    invoke-virtual {p2, p3}, Ljava/net/URLConnection;->getHeaderField(Ljava/lang/String;)Ljava/lang/String;

    .line 29
    move-result-object p3

    .line 30
    invoke-static {p3}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    .line 33
    move-result v3

    .line 34
    if-eqz v3, :cond_44

    .line 36
    const-string p3, "Range"

    .line 38
    if-nez p1, :cond_2d

    .line 40
    new-instance p1, Ljava/util/HashMap;

    .line 42
    invoke-direct {p1}, Ljava/util/HashMap;-><init>()V

    .line 45
    goto :goto_37

    .line 46
    :cond_2d
    invoke-interface {p1, p3}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z

    .line 49
    move-result v3

    .line 50
    if-eqz v3, :cond_37

    .line 52
    invoke-static {p2}, La5/b;->c(Ljava/net/HttpURLConnection;)V

    .line 55
    return-wide v1

    .line 56
    :cond_37
    :goto_37
    const-string v1, "bytes=0-"

    .line 58
    invoke-interface {p1, p3, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    .line 61
    invoke-static {p2}, La5/b;->c(Ljava/net/HttpURLConnection;)V

    .line 64
    invoke-static {p0, p1, p2, v0}, LU4/f;->I(LY4/c;Ljava/util/Map;Ljava/net/HttpURLConnection;Z)J

    .line 67
    move-result-wide p0

    .line 68
    return-wide p0

    .line 69
    :cond_44
    invoke-static {p3}, Ljava/lang/Long;->parseLong(Ljava/lang/String;)J

    .line 72
    move-result-wide p0

    .line 73
    const-wide/16 v3, 0x0

    .line 75
    cmp-long p3, p0, v3

    .line 77
    if-gtz p3, :cond_52

    .line 79
    invoke-static {p2}, La5/b;->c(Ljava/net/HttpURLConnection;)V

    .line 82
    return-wide v1

    .line 83
    :cond_52
    return-wide p0
.end method

.method public static J(LW0/g;LA1/f;)LR1/f;
    .registers 4

    .line 1
    iget-object p1, p1, LA1/f;->o:Ljava/lang/Object;

    .line 3
    check-cast p1, Ljava/io/IOException;

    .line 5
    instance-of v0, p1, Lt0/w;

    .line 7
    const/4 v1, 0x0

    .line 8
    if-nez v0, :cond_a

    .line 10
    goto :goto_27

    .line 11
    :cond_a
    check-cast p1, Lt0/w;

    .line 13
    iget p1, p1, Lt0/w;->o:I

    .line 15
    const/16 v0, 0x193

    .line 17
    if-eq p1, v0, :cond_28

    .line 19
    const/16 v0, 0x194

    .line 21
    if-eq p1, v0, :cond_28

    .line 23
    const/16 v0, 0x19a

    .line 25
    if-eq p1, v0, :cond_28

    .line 27
    const/16 v0, 0x1a0

    .line 29
    if-eq p1, v0, :cond_28

    .line 31
    const/16 v0, 0x1f4

    .line 33
    if-eq p1, v0, :cond_28

    .line 35
    const/16 v0, 0x1f7

    .line 37
    if-ne p1, v0, :cond_27

    .line 39
    goto :goto_28

    .line 40
    :cond_27
    :goto_27
    return-object v1

    .line 41
    :cond_28
    :goto_28
    const/4 p1, 0x1

    .line 42
    invoke-virtual {p0, p1}, LW0/g;->a(I)Z

    .line 45
    move-result v0

    .line 46
    if-eqz v0, :cond_38

    .line 48
    new-instance p0, LR1/f;

    .line 50
    const-wide/32 v0, 0x493e0

    .line 53
    invoke-direct {p0, p1, v0, v1}, LR1/f;-><init>(IJ)V

    .line 56
    return-object p0

    .line 57
    :cond_38
    const/4 p1, 0x2

    .line 58
    invoke-virtual {p0, p1}, LW0/g;->a(I)Z

    .line 61
    move-result p0

    .line 62
    if-eqz p0, :cond_48

    .line 64
    new-instance p0, LR1/f;

    .line 66
    const-wide/32 v0, 0xea60

    .line 69
    invoke-direct {p0, p1, v0, v1}, LR1/f;-><init>(IJ)V

    .line 72
    return-object p0

    .line 73
    :cond_48
    return-object v1
.end method

.method public static K()LU4/f;
    .registers 2

    .line 1
    sget-object v0, LU4/f;->i:LU4/f;

    .line 3
    if-nez v0, :cond_19

    .line 5
    const-class v0, LU4/f;

    .line 7
    monitor-enter v0

    .line 8
    :try_start_7
    sget-object v1, LU4/f;->i:LU4/f;

    .line 10
    if-nez v1, :cond_15

    .line 12
    new-instance v1, LU4/f;

    .line 14
    invoke-direct {v1}, Ljava/lang/Object;-><init>()V

    .line 17
    sput-object v1, LU4/f;->i:LU4/f;

    .line 19
    goto :goto_15

    .line 20
    :catchall_13
    move-exception v1

    .line 21
    goto :goto_17

    .line 22
    :cond_15
    :goto_15
    monitor-exit v0

    .line 23
    goto :goto_19

    .line 24
    :goto_17
    monitor-exit v0
    :try_end_18
    .catchall {:try_start_7 .. :try_end_18} :catchall_13

    .line 25
    throw v1

    .line 26
    :cond_19
    :goto_19
    sget-object v0, LU4/f;->i:LU4/f;

    .line 28
    return-object v0
.end method

.method public static M(LA1/f;)J
    .registers 4

    .line 1
    iget-object v0, p0, LA1/f;->o:Ljava/lang/Object;

    .line 3
    check-cast v0, Ljava/io/IOException;

    .line 5
    :goto_4
    if-eqz v0, :cond_2f

    .line 7
    instance-of v1, v0, Ln0/G;

    .line 9
    if-nez v1, :cond_29

    .line 11
    instance-of v1, v0, Ljava/io/FileNotFoundException;

    .line 13
    if-nez v1, :cond_29

    .line 15
    instance-of v1, v0, Lt0/s;

    .line 17
    if-nez v1, :cond_29

    .line 19
    instance-of v1, v0, LW0/l;

    .line 21
    if-nez v1, :cond_29

    .line 23
    instance-of v1, v0, Lt0/i;

    .line 25
    if-eqz v1, :cond_24

    .line 27
    move-object v1, v0

    .line 28
    check-cast v1, Lt0/i;

    .line 30
    iget v1, v1, Lt0/i;->i:I

    .line 32
    const/16 v2, 0x7d8

    .line 34
    if-ne v1, v2, :cond_24

    .line 36
    goto :goto_29

    .line 37
    :cond_24
    invoke-virtual {v0}, Ljava/lang/Throwable;->getCause()Ljava/lang/Throwable;

    .line 40
    move-result-object v0

    .line 41
    goto :goto_4

    .line 42
    :cond_29
    :goto_29
    const-wide v0, -0x7fffffffffffffffL  # -4.9E-324

    .line 47
    goto :goto_3c

    .line 48
    :cond_2f
    iget p0, p0, LA1/f;->n:I

    .line 50
    add-int/lit8 p0, p0, -0x1

    .line 52
    mul-int/lit16 p0, p0, 0x3e8

    .line 54
    const/16 v0, 0x1388

    .line 56
    invoke-static {p0, v0}, Ljava/lang/Math;->min(II)I

    .line 59
    move-result p0

    .line 60
    int-to-long v0, p0

    .line 61
    :goto_3c
    return-wide v0
.end method

.method public static W(LY4/c;Ljava/util/Map;Landroid/support/v4/media/session/q;)V
    .registers 11

    .line 1
    iget-object v0, p2, Landroid/support/v4/media/session/q;->p:Ljava/lang/Object;

    .line 3
    check-cast v0, LU4/d;

    .line 5
    iget-object v1, p2, Landroid/support/v4/media/session/q;->o:Ljava/lang/Object;

    .line 7
    check-cast v1, LY4/c;

    .line 9
    const-string v2, "video_downloader"

    .line 11
    const/4 v3, 0x7

    .line 12
    const/4 v4, 0x6

    .line 13
    :try_start_c
    iget-object v5, p0, LY4/c;->u:Ljava/lang/String;

    .line 15
    const/4 v6, 0x0

    .line 16
    invoke-static {v5, p1, v6}, LH6/l;->w0(Ljava/lang/String;Ljava/util/Map;I)LX4/a;

    .line 19
    move-result-object p1

    .line 20
    iget-boolean v5, p1, LX4/a;->f:Z

    .line 22
    if-eqz v5, :cond_4a

    .line 24
    iget-object v5, p0, LY4/c;->i:Ljava/lang/String;

    .line 26
    invoke-static {v5}, La5/b;->d(Ljava/lang/String;)Ljava/lang/String;

    .line 29
    move-result-object v5

    .line 30
    new-instance v6, Ljava/io/File;

    .line 32
    sget-object v7, La5/b;->b:LK2/a;

    .line 34
    iget-object v7, v7, LK2/a;->i:Ljava/lang/String;

    .line 36
    invoke-direct {v6, v7, v5}, Ljava/io/File;-><init>(Ljava/lang/String;Ljava/lang/String;)V

    .line 39
    invoke-virtual {v6}, Ljava/io/File;->exists()Z

    .line 42
    move-result v5

    .line 43
    if-nez v5, :cond_32

    .line 45
    invoke-virtual {v6}, Ljava/io/File;->mkdir()Z

    .line 48
    goto :goto_32

    .line 49
    :catch_30
    move-exception p0

    .line 50
    goto :goto_63

    .line 51
    :cond_32
    :goto_32
    invoke-static {v6, p1}, LH6/l;->f(Ljava/io/File;LX4/a;)V

    .line 54
    invoke-virtual {v6}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;

    .line 57
    move-result-object v5

    .line 58
    iput-object v5, p0, LY4/c;->D:Ljava/lang/String;

    .line 60
    const/4 v5, 0x1

    .line 61
    iput v5, p0, LY4/c;->v:I

    .line 63
    iget-object p0, p0, LY4/c;->t:Ljava/lang/String;

    .line 65
    iput-object p0, v1, LY4/c;->t:Ljava/lang/String;

    .line 67
    iget-object p0, p2, Landroid/support/v4/media/session/q;->n:Ljava/lang/Object;

    .line 69
    check-cast p0, Ljava/util/Map;

    .line 71
    invoke-static {v0, v1, p1, p0}, LU4/d;->a(LU4/d;LY4/c;LX4/a;Ljava/util/Map;)V

    .line 74
    goto :goto_8a

    .line 75
    :cond_4a
    const/4 p1, 0x2

    .line 76
    iput p1, p0, LY4/c;->v:I

    .line 78
    const-string p0, "onLiveM3U8Callback cannot be cached."

    .line 80
    invoke-static {v2, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    .line 83
    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 86
    iput v4, v1, LY4/c;->s:I

    .line 88
    iget-object p0, v0, LU4/d;->r:Ljava/lang/Object;

    .line 90
    check-cast p0, LF0/d;

    .line 92
    invoke-virtual {p0, v3, v1}, Landroid/os/Handler;->obtainMessage(ILjava/lang/Object;)Landroid/os/Message;

    .line 95
    move-result-object p0

    .line 96
    invoke-virtual {p0}, Landroid/os/Message;->sendToTarget()V
    :try_end_62
    .catch Ljava/lang/Exception; {:try_start_c .. :try_end_62} :catch_30

    .line 99
    goto :goto_8a

    .line 100
    :goto_63
    invoke-virtual {p0}, Ljava/lang/Throwable;->printStackTrace()V

    .line 103
    new-instance p1, Ljava/lang/StringBuilder;

    .line 105
    const-string p2, "onM3U8InfoFailed : "

    .line 107
    invoke-direct {p1, p2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 110
    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    .line 113
    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    .line 116
    move-result-object p1

    .line 117
    invoke-static {v2, p1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    .line 120
    invoke-static {p0}, La5/b;->h(Ljava/lang/Exception;)I

    .line 123
    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 126
    iput v4, v1, LY4/c;->s:I

    .line 128
    iget-object p0, v0, LU4/d;->r:Ljava/lang/Object;

    .line 130
    check-cast p0, LF0/d;

    .line 132
    invoke-virtual {p0, v3, v1}, Landroid/os/Handler;->obtainMessage(ILjava/lang/Object;)Landroid/os/Message;

    .line 135
    move-result-object p0

    .line 136
    invoke-virtual {p0}, Landroid/os/Message;->sendToTarget()V

    .line 139
    :goto_8a
    return-void
.end method


# virtual methods
.method public A(Landroidx/media3/common/b;)LG1/m;
    .registers 3

    .line 1
    new-instance p1, Ljava/lang/IllegalStateException;

    .line 3
    const-string v0, "This SubtitleParser.Factory doesn\'t support any formats."

    .line 5
    invoke-direct {p1, v0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    .line 8
    throw p1
.end method

.method public B()V
    .registers 1

    .line 1
    return-void
.end method

.method public C(J)V
    .registers 3

    .line 1
    return-void
.end method

.method public D()Z
    .registers 2

    .line 1
    const/4 v0, 0x0

    .line 2
    return v0
.end method

.method public E(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V
    .registers 7

    .line 1
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;

    .line 3
    new-instance v1, Ljava/lang/StringBuilder;

    .line 5
    const-string v2, "["

    .line 7
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 10
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    .line 13
    const-string p1, "] "

    .line 15
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    .line 18
    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    .line 21
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    .line 24
    move-result-object p1

    .line 25
    invoke-virtual {v0, p1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 28
    invoke-virtual {p3, v0}, Ljava/lang/Throwable;->printStackTrace(Ljava/io/PrintStream;)V

    .line 31
    return-void
.end method

.method public G()V
    .registers 1

    .line 1
    return-void
.end method

.method public H(Ljava/lang/String;)V
    .registers 2

    .line 1
    return-void
.end method

.method public L(I)I
    .registers 3

    .line 1
    const/4 v0, 0x7

    .line 2
    if-ne p1, v0, :cond_5

    .line 4
    const/4 p1, 0x6

    .line 5
    goto :goto_6

    .line 6
    :cond_5
    const/4 p1, 0x3

    .line 7
    :goto_6
    return p1
.end method

.method public N(Landroid/content/pm/PackageManager;Ljava/lang/String;)[Landroid/content/pm/Signature;
    .registers 4

    .line 1
    const/16 v0, 0x40

    .line 3
    invoke-virtual {p1, p2, v0}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;

    .line 6
    move-result-object p1

    .line 7
    iget-object p1, p1, Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;

    .line 9
    return-object p1
.end method

.method public O(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public P(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public Q(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public R(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public S(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public T(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public U(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public V(LY4/c;)V
    .registers 2

    .line 1
    return-void
.end method

.method public X()V
    .registers 1

    .line 1
    return-void
.end method

.method public Y()V
    .registers 1

    .line 1
    return-void
.end method

.method public Z(Ljava/lang/String;)V
    .registers 2

    .line 1
    return-void
.end method

.method public synthetic a()V
    .registers 1

    .line 1
    return-void
.end method

.method public b(I)Landroid/media/MediaCodecInfo;
    .registers 2

    .line 1
    invoke-static {p1}, Landroid/media/MediaCodecList;->getCodecInfoAt(I)Landroid/media/MediaCodecInfo;

    .line 4
    move-result-object p1

    .line 5
    return-object p1
.end method

.method public c(Ljava/util/logging/Level;Ljava/lang/String;)V
    .registers 6

    .line 1
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;

    .line 3
    new-instance v1, Ljava/lang/StringBuilder;

    .line 5
    const-string v2, "["

    .line 7
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 10
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    .line 13
    const-string p1, "] "

    .line 15
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    .line 18
    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    .line 21
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    .line 24
    move-result-object p1

    .line 25
    invoke-virtual {v0, p1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 28
    return-void
.end method

.method public create()Ljava/lang/Object;
    .registers 3

    .line 1
    :try_start_0
    new-instance v0, LX2/g;

    .line 3
    const-string v1, "SHA-256"

    .line 5
    invoke-static {v1}, Ljava/security/MessageDigest;->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;

    .line 8
    move-result-object v1

    .line 9
    invoke-direct {v0, v1}, LX2/g;-><init>(Ljava/security/MessageDigest;)V
    :try_end_b
    .catch Ljava/security/NoSuchAlgorithmException; {:try_start_0 .. :try_end_b} :catch_c

    .line 12
    return-object v0

    .line 13
    :catch_c
    move-exception v0

    .line 14
    new-instance v1, Ljava/lang/RuntimeException;

    .line 16
    invoke-direct {v1, v0}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/Throwable;)V

    .line 19
    throw v1
.end method

.method public synthetic d(Ln0/d0;)V
    .registers 2

    .line 1
    return-void
.end method

.method public e([BLjava/lang/Object;Ljava/security/MessageDigest;)V
    .registers 4

    .line 1
    return-void
.end method

.method public f(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;
    .registers 4

    .line 1
    invoke-static {p1, p2, p3}, Landroid/graphics/Bitmap;->createBitmap(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;

    .line 4
    move-result-object p1

    .line 5
    return-object p1
.end method

.method public g(I)LM0/e;
    .registers 3

    .line 1
    new-instance v0, LM0/H;

    .line 3
    invoke-direct {v0}, LM0/H;-><init>()V

    .line 6
    mul-int/lit8 p1, p1, 0x2

    .line 8
    invoke-static {p1}, Lo0/g;->i(I)Lt0/l;

    .line 11
    move-result-object p1

    .line 12
    invoke-virtual {v0, p1}, LM0/H;->o(Lt0/l;)J

    .line 15
    return-object v0
.end method

.method public synthetic h()V
    .registers 1

    .line 1
    return-void
.end method

.method public i(Landroid/graphics/Bitmap;)V
    .registers 2

    .line 1
    invoke-virtual {p1}, Landroid/graphics/Bitmap;->recycle()V

    .line 4
    return-void
.end method

.method public j(Landroidx/media3/common/b;)I
    .registers 2

    .line 1
    const/4 p1, 0x1

    .line 2
    return p1
.end method

.method public synthetic k()LM0/d;
    .registers 2

    .line 1
    const/4 v0, 0x0

    return-object v0
.end method

.method public l(ILjava/io/Serializable;)V
    .registers 6

    .line 1
    packed-switch p1, :pswitch_data_3a

    .line 4
    :pswitch_3  #0x9
    const-string v0, ""

    .line 6
    goto :goto_23

    .line 7
    :pswitch_6  #0xb
    const-string v0, "RESULT_DELETE_SKIP_FILE_SUCCESS"

    .line 9
    goto :goto_23

    .line 10
    :pswitch_9  #0xa
    const-string v0, "RESULT_INSTALL_SKIP_FILE_SUCCESS"

    .line 12
    goto :goto_23

    .line 13
    :pswitch_c  #0x8
    const-string v0, "RESULT_PARSE_EXCEPTION"

    .line 15
    goto :goto_23

    .line 16
    :pswitch_f  #0x7
    const-string v0, "RESULT_IO_EXCEPTION"

    .line 18
    goto :goto_23

    .line 19
    :pswitch_12  #0x6
    const-string v0, "RESULT_BASELINE_PROFILE_NOT_FOUND"

    .line 21
    goto :goto_23

    .line 22
    :pswitch_15  #0x5
    const-string v0, "RESULT_DESIRED_FORMAT_UNSUPPORTED"

    .line 24
    goto :goto_23

    .line 25
    :pswitch_18  #0x4
    const-string v0, "RESULT_NOT_WRITABLE"

    .line 27
    goto :goto_23

    .line 28
    :pswitch_1b  #0x3
    const-string v0, "RESULT_UNSUPPORTED_ART_VERSION"

    .line 30
    goto :goto_23

    .line 31
    :pswitch_1e  #0x2
    const-string v0, "RESULT_ALREADY_INSTALLED"

    .line 33
    goto :goto_23

    .line 34
    :pswitch_21  #0x1
    const-string v0, "RESULT_INSTALL_SUCCESS"

    .line 36
    :goto_23
    const/4 v1, 0x6

    .line 37
    const-string v2, "ProfileInstaller"

    .line 39
    if-eq p1, v1, :cond_33

    .line 41
    const/4 v1, 0x7

    .line 42
    if-eq p1, v1, :cond_33

    .line 44
    const/16 v1, 0x8

    .line 46
    if-eq p1, v1, :cond_33

    .line 48
    invoke-static {v2, v0}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I

    .line 51
    goto :goto_38

    .line 52
    :cond_33
    check-cast p2, Ljava/lang/Throwable;

    .line 54
    invoke-static {v2, v0, p2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 57
    :goto_38
    return-void

    nop

    .line 59
    :pswitch_data_3a
    .packed-switch 0x1
        :pswitch_21  #00000001
        :pswitch_1e  #00000002
        :pswitch_1b  #00000003
        :pswitch_18  #00000004
        :pswitch_15  #00000005
        :pswitch_12  #00000006
        :pswitch_f  #00000007
        :pswitch_c  #00000008
        :pswitch_3  #00000009
        :pswitch_9  #0000000a
        :pswitch_6  #0000000b
    .end packed-switch
.end method

.method public m(LF0/m;Landroidx/media3/common/b;)LF0/j;
    .registers 5

    .line 1
    iget-object p1, p2, Landroidx/media3/common/b;->s:Landroidx/media3/common/DrmInitData;

    .line 3
    if-nez p1, :cond_6

    .line 5
    const/4 p1, 0x0

    .line 6
    return-object p1

    .line 7
    :cond_6
    new-instance p1, LF0/r;

    .line 9
    new-instance p2, LF0/i;

    .line 11
    new-instance v0, LF0/G;

    .line 13
    invoke-direct {v0}, Ljava/lang/Exception;-><init>()V

    .line 16
    const/16 v1, 0x1771

    .line 18
    invoke-direct {p2, v0, v1}, LF0/i;-><init>(Ljava/lang/Throwable;I)V

    .line 21
    invoke-direct {p1, p2}, LF0/r;-><init>(LF0/i;)V

    .line 24
    return-object p1
.end method

.method public n(Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaCodecInfo$CodecCapabilities;)Z
    .registers 4

    .line 1
    const-string p3, "secure-playback"

    .line 3
    invoke-virtual {p3, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 6
    move-result p1

    .line 7
    if-eqz p1, :cond_12

    .line 9
    const-string p1, "video/avc"

    .line 11
    invoke-virtual {p1, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 14
    move-result p1

    .line 15
    if-eqz p1, :cond_12

    .line 17
    const/4 p1, 0x1

    .line 18
    goto :goto_13

    .line 19
    :cond_12
    const/4 p1, 0x0

    .line 20
    :goto_13
    return p1
.end method

.method public o(La1/l;)J
    .registers 4

    .line 1
    const-wide/16 v0, -0x1

    .line 3
    return-wide v0
.end method

.method public onFailure(Lokhttp3/Call;Ljava/io/IOException;)V
    .registers 3

    .line 1
    return-void
.end method

.method public onResponse(Lokhttp3/Call;Lokhttp3/Response;)V
    .registers 3

    .line 1
    return-void
.end method

.method public synthetic p(LF0/m;Landroidx/media3/common/b;)LF0/o;
    .registers 3

    .line 1
    sget-object p1, LF0/o;->a:LA0/q;

    return-object p1
.end method

.method public q()V
    .registers 3

    .line 1
    const-string v0, "ProfileInstaller"

    .line 3
    const-string v1, "DIAGNOSTIC_PROFILE_IS_COMPRESSED"

    .line 5
    invoke-static {v0, v1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I

    .line 8
    return-void
.end method

.method public r()La1/z;
    .registers 4

    .line 1
    new-instance v0, LF1/d;

    .line 3
    const-wide v1, -0x7fffffffffffffffL  # -4.9E-324

    .line 8
    invoke-direct {v0, v1, v2}, LF1/d;-><init>(J)V

    .line 11
    return-object v0
.end method

.method public synthetic release()V
    .registers 1

    .line 1
    return-void
.end method

.method public s(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;
    .registers 4

    .line 1
    invoke-static {p1, p2, p3}, Landroid/graphics/Bitmap;->createBitmap(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;

    .line 4
    move-result-object p1

    .line 5
    return-object p1
.end method

.method public supportsFormat(Landroidx/media3/common/b;)Z
    .registers 2

    .line 1
    const/4 p1, 0x0

    .line 2
    return p1
.end method

.method public synthetic t()V
    .registers 1

    .line 1
    return-void
.end method

.method public u(Landroidx/media3/common/b;)I
    .registers 2

    .line 1
    iget-object p1, p1, Landroidx/media3/common/b;->s:Landroidx/media3/common/DrmInitData;

    .line 3
    if-eqz p1, :cond_6

    .line 5
    const/4 p1, 0x1

    .line 6
    goto :goto_7

    .line 7
    :cond_6
    const/4 p1, 0x0

    .line 8
    :goto_7
    return p1
.end method

.method public v(Ljava/lang/String;Landroid/media/MediaCodecInfo$CodecCapabilities;)Z
    .registers 3

    .line 1
    const/4 p1, 0x0

    .line 2
    return p1
.end method

.method public w()I
    .registers 2

    .line 1
    invoke-static {}, Landroid/media/MediaCodecList;->getCodecCount()I

    .line 4
    move-result v0

    .line 5
    return v0
.end method

.method public synthetic x()V
    .registers 1

    .line 1
    return-void
.end method

.method public y(Landroid/os/Looper;LB0/C;)V
    .registers 3

    .line 1
    return-void
.end method

.method public z(I)V
    .registers 2

    .line 1
    return-void
.end method
