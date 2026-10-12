.class public final Lt3/e;
.super Lt3/a;
.source "SourceFile"


# static fields
.field public static final synthetic i:I


# instance fields
.field public e:Lcom/fongmi/android/tv/bean/Live;

.field public f:Ljava/util/List;

.field public g:Ljava/util/List;

.field public h:Ljava/util/ArrayList;


# virtual methods
.method public final a()Lcom/fongmi/android/tv/bean/Config;
    .registers 2

    .line 1
    invoke-static {}, Lcom/fongmi/android/tv/bean/Config;->live()Lcom/fongmi/android/tv/bean/Config;

    .line 4
    move-result-object v0

    .line 5
    return-object v0
.end method

.method public final e()Ljava/lang/String;
    .registers 2

    .line 1
    const-string v0, "e"

    .line 3
    return-object v0
.end method

.method public final g(Lcom/fongmi/android/tv/bean/Config;)V
    .registers 10

    .line 1
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 4
    move-result-object v0

    .line 5
    invoke-static {v0}, LU3/f;->a(Ljava/lang/String;)Ljava/lang/String;

    .line 8
    move-result-object v0

    .line 9
    const-string v1, "e"

    .line 11
    invoke-static {v0, v1}, Ls3/a;->b(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    .line 14
    move-result-object v0

    .line 15
    invoke-static {v0}, LI7/g;->u(Ljava/lang/String;)Z

    .line 18
    move-result v1

    .line 19
    const/4 v2, 0x0

    .line 20
    const/4 v3, 0x1

    .line 21
    if-eqz v1, :cond_8a

    .line 23
    invoke-static {v0}, LI7/g;->w(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 26
    move-result-object v0

    .line 27
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->getAsJsonObject()Lcom/google/gson/JsonObject;

    .line 30
    move-result-object v0

    .line 31
    const-string v1, "msg"

    .line 33
    invoke-virtual {v0, v1}, Lcom/google/gson/JsonObject;->has(Ljava/lang/String;)Z

    .line 36
    move-result v4

    .line 37
    if-nez v4, :cond_7c

    .line 39
    const-string v1, "urls"

    .line 41
    invoke-virtual {v0, v1}, Lcom/google/gson/JsonObject;->has(Ljava/lang/String;)Z

    .line 44
    move-result v4

    .line 45
    if-eqz v4, :cond_78

    .line 47
    invoke-virtual {v0, v1}, Lcom/google/gson/JsonObject;->getAsJsonArray(Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 50
    move-result-object v0

    .line 51
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->toString()Ljava/lang/String;

    .line 54
    move-result-object v0

    .line 55
    invoke-static {v0}, Lcom/fongmi/android/tv/bean/Depot;->arrayFrom(Ljava/lang/String;)Ljava/util/List;

    .line 58
    move-result-object v0

    .line 59
    new-instance v1, Ljava/util/ArrayList;

    .line 61
    invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V

    .line 64
    invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;

    .line 67
    move-result-object v0

    .line 68
    :goto_43
    invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z

    .line 71
    move-result v4

    .line 72
    if-eqz v4, :cond_57

    .line 74
    invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    .line 77
    move-result-object v4

    .line 78
    check-cast v4, Lcom/fongmi/android/tv/bean/Depot;

    .line 80
    invoke-static {v4, v3}, Lcom/fongmi/android/tv/bean/Config;->find(Lcom/fongmi/android/tv/bean/Depot;I)Lcom/fongmi/android/tv/bean/Config;

    .line 83
    move-result-object v4

    .line 84
    invoke-virtual {v1, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    .line 87
    goto :goto_43

    .line 88
    :cond_57
    invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z

    .line 91
    move-result v0

    .line 92
    if-nez v0, :cond_70

    .line 94
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;

    .line 97
    move-result-object v0

    .line 98
    check-cast v0, Lcom/fongmi/android/tv/bean/Config;

    .line 100
    iput-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 102
    invoke-virtual {p0, v0}, Lt3/e;->g(Lcom/fongmi/android/tv/bean/Config;)V

    .line 105
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 108
    move-result-object p1

    .line 109
    invoke-static {p1}, Lcom/fongmi/android/tv/bean/Config;->delete(Ljava/lang/String;)V

    .line 112
    goto :goto_db

    .line 113
    :cond_70
    new-instance p1, Ljava/lang/Exception;

    .line 115
    const-string v0, "Depot urls is empty"

    .line 117
    invoke-direct {p1, v0}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

    .line 120
    throw p1

    .line 121
    :cond_78
    invoke-virtual {p0, p1, v0}, Lt3/e;->o(Lcom/fongmi/android/tv/bean/Config;Lcom/google/gson/JsonObject;)V

    .line 124
    goto :goto_db

    .line 125
    :cond_7c
    new-instance p1, Ljava/lang/Exception;

    .line 127
    invoke-virtual {v0, v1}, Lcom/google/gson/JsonObject;->get(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 130
    move-result-object v0

    .line 131
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->getAsString()Ljava/lang/String;

    .line 134
    move-result-object v0

    .line 135
    invoke-direct {p1, v0}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

    .line 138
    throw p1

    .line 139
    :cond_8a
    new-instance v1, Lcom/fongmi/android/tv/bean/Live;

    .line 141
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 144
    move-result-object v4

    .line 145
    invoke-static {v4}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;

    .line 148
    move-result-object v5

    .line 149
    invoke-virtual {v5}, Landroid/net/Uri;->getLastPathSegment()Ljava/lang/String;

    .line 152
    move-result-object v6

    .line 153
    if-nez v6, :cond_9d

    .line 155
    const-string v6, ""

    .line 157
    goto :goto_a1

    .line 158
    :cond_9d
    invoke-virtual {v6}, Ljava/lang/String;->trim()Ljava/lang/String;

    .line 161
    move-result-object v6

    .line 162
    :goto_a1
    invoke-static {v5}, LU3/f;->s(Landroid/net/Uri;)Ljava/lang/String;

    .line 165
    move-result-object v5

    .line 166
    invoke-virtual {v6}, Ljava/lang/String;->isEmpty()Z

    .line 169
    move-result v7

    .line 170
    if-nez v7, :cond_ad

    .line 172
    move-object v4, v6

    .line 173
    goto :goto_b4

    .line 174
    :cond_ad
    invoke-virtual {v5}, Ljava/lang/String;->isEmpty()Z

    .line 177
    move-result v6

    .line 178
    if-nez v6, :cond_b4

    .line 180
    move-object v4, v5

    .line 181
    :cond_b4
    :goto_b4
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 184
    move-result-object v5

    .line 185
    invoke-direct {v1, v4, v5}, Lcom/fongmi/android/tv/bean/Live;-><init>(Ljava/lang/String;Ljava/lang/String;)V

    .line 188
    invoke-virtual {v1}, Lcom/fongmi/android/tv/bean/Live;->sync()Lcom/fongmi/android/tv/bean/Live;

    .line 191
    move-result-object v1

    .line 192
    new-instance v4, Ljava/util/ArrayList;

    .line 194
    new-array v5, v3, [Ljava/lang/Object;

    .line 196
    aput-object v1, v5, v2

    .line 198
    new-instance v6, Ljava/util/ArrayList;

    .line 200
    invoke-direct {v6, v3}, Ljava/util/ArrayList;-><init>(I)V

    .line 203
    aget-object v3, v5, v2

    .line 205
    invoke-static {v3, v6, v3, v6}, LA0/c;->q(Ljava/lang/Object;Ljava/util/ArrayList;Ljava/lang/Object;Ljava/util/ArrayList;)Ljava/util/List;

    .line 208
    move-result-object v3

    .line 209
    invoke-direct {v4, v3}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V

    .line 212
    iput-object v4, p0, Lt3/e;->f:Ljava/util/List;

    .line 214
    invoke-static {v1, v0}, Lv3/c;->c(Lcom/fongmi/android/tv/bean/Live;Ljava/lang/String;)V

    .line 217
    invoke-virtual {p0, p1, v1, v2}, Lt3/e;->p(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Live;Z)V

    .line 220
    :goto_db
    return-void
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
    sget-object v2, Lz3/c;->o:Lz3/c;

    .line 23
    invoke-direct {v1, v2}, Lz3/d;-><init>(Lz3/c;)V

    .line 26
    invoke-virtual {v0, v1}, LN6/d;->e(Ljava/lang/Object;)V

    .line 29
    return-void
.end method

.method public final l(Lcom/fongmi/android/tv/bean/Config;)V
    .registers 3

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
    invoke-virtual {v0}, Lt3/a;->d()Lcom/fongmi/android/tv/bean/Config;

    .line 19
    move-result-object v0

    .line 20
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 23
    move-result-object v0

    .line 24
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 27
    move-result p1

    .line 28
    iput-boolean p1, p0, Lt3/a;->b:Z

    .line 30
    return-void
.end method

.method public final m()Lcom/fongmi/android/tv/bean/Live;
    .registers 2

    .line 1
    iget-object v0, p0, Lt3/e;->e:Lcom/fongmi/android/tv/bean/Live;

    .line 3
    if-nez v0, :cond_9

    .line 5
    new-instance v0, Lcom/fongmi/android/tv/bean/Live;

    .line 7
    invoke-direct {v0}, Lcom/fongmi/android/tv/bean/Live;-><init>()V

    .line 10
    :cond_9
    return-object v0
.end method

.method public final n()Ljava/util/List;
    .registers 2

    .line 1
    iget-object v0, p0, Lt3/e;->f:Ljava/util/List;

    .line 3
    if-nez v0, :cond_b

    .line 5
    new-instance v0, Ljava/util/ArrayList;

    .line 7
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

    .line 10
    iput-object v0, p0, Lt3/e;->f:Ljava/util/List;

    .line 12
    :cond_b
    return-object v0
.end method

.method public final o(Lcom/fongmi/android/tv/bean/Config;Lcom/google/gson/JsonObject;)V
    .registers 6

    .line 1
    const-string v0, "headers"

    .line 3
    invoke-static {p2, v0}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 6
    move-result-object v0

    .line 7
    invoke-static {v0}, Lcom/github/catvod/bean/d;->a(Lcom/google/gson/JsonArray;)Ljava/util/List;

    .line 10
    move-result-object v0

    .line 11
    invoke-static {}, Lb4/d;->k()Lc4/e;

    .line 14
    move-result-object v1

    .line 15
    iget-object v1, v1, Lc4/e;->i:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 17
    invoke-virtual {v1, v0}, Ljava/util/concurrent/CopyOnWriteArrayList;->addAll(Ljava/util/Collection;)Z

    .line 20
    const-string v0, "proxy"

    .line 22
    invoke-static {p2, v0}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 25
    move-result-object v0

    .line 26
    invoke-static {v0}, Lcom/github/catvod/bean/f;->a(Lcom/google/gson/JsonArray;)Ljava/util/List;

    .line 29
    move-result-object v0

    .line 30
    invoke-static {v0}, Lt3/a;->k(Ljava/util/List;)V

    .line 33
    const-string v0, "rules"

    .line 35
    invoke-static {p2, v0}, Lt3/a;->c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 38
    move-result-object v0

    .line 39
    invoke-static {v0}, Lcom/fongmi/android/tv/bean/Rule;->arrayFrom(Lcom/google/gson/JsonElement;)Ljava/util/List;

    .line 42
    move-result-object v0

    .line 43
    iput-object v0, p0, Lt3/e;->g:Ljava/util/List;

    .line 45
    sget-object v0, Lt3/f;->a:Li3/t;

    .line 47
    const/4 v1, 0x1

    .line 48
    iput-boolean v1, v0, Li3/t;->n:Z

    .line 50
    const-string v0, "hosts"

    .line 52
    invoke-static {p2, v0}, LI7/g;->B(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 55
    move-result-object v0

    .line 56
    invoke-static {v0}, Lt3/a;->j(Ljava/util/ArrayList;)V

    .line 59
    const-string v0, "ads"

    .line 61
    invoke-static {p2, v0}, LI7/g;->B(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 64
    move-result-object v0

    .line 65
    iput-object v0, p0, Lt3/e;->h:Ljava/util/ArrayList;

    .line 67
    sget-object v0, Lt3/f;->a:Li3/t;

    .line 69
    iput-boolean v1, v0, Li3/t;->n:Z

    .line 71
    const-string v0, "spider"

    .line 73
    invoke-static {p2, v0}, LI7/g;->C(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;

    .line 76
    move-result-object v0

    .line 77
    sget-object v1, Lu3/a;->a:Lg6/c;

    .line 79
    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 82
    invoke-static {v0}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    .line 85
    move-result v2

    .line 86
    if-eqz v2, :cond_58

    .line 88
    goto :goto_63

    .line 89
    :cond_58
    invoke-static {v0}, Ld4/c;->g(Ljava/lang/String;)Ljava/lang/String;

    .line 92
    move-result-object v2

    .line 93
    iget-object v1, v1, Lg6/c;->n:Ljava/lang/Object;

    .line 95
    check-cast v1, LA2/a;

    .line 97
    invoke-virtual {v1, v2, v0}, LA2/a;->f(Ljava/lang/String;Ljava/lang/String;)V

    .line 100
    :goto_63
    const-string v1, "lives"

    .line 102
    invoke-static {p2, v1}, LI7/g;->A(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/util/ArrayList;

    .line 105
    move-result-object p2

    .line 106
    invoke-static {p2}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 109
    move-result-object p2

    .line 110
    new-instance v1, Ll7/a;

    .line 112
    const/4 v2, 0x3

    .line 113
    invoke-direct {v1, v0, v2}, Ll7/a;-><init>(Ljava/lang/String;I)V

    .line 116
    invoke-interface {p2, v1}, Lj$/util/stream/Stream;->map(Ljava/util/function/Function;)Lj$/util/stream/Stream;

    .line 119
    move-result-object p2

    .line 120
    invoke-interface {p2}, Lj$/util/stream/Stream;->distinct()Lj$/util/stream/Stream;

    .line 123
    move-result-object p2

    .line 124
    new-instance v0, LS6/b;

    .line 126
    const/16 v1, 0xa

    .line 128
    invoke-direct {v0, v1}, LS6/b;-><init>(I)V

    .line 131
    invoke-static {v0}, Lj$/util/stream/Collectors;->toCollection(Ljava/util/function/Supplier;)Lj$/util/stream/Collector;

    .line 134
    move-result-object v0

    .line 135
    invoke-interface {p2, v0}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 138
    move-result-object p2

    .line 139
    check-cast p2, Ljava/util/List;

    .line 141
    iput-object p2, p0, Lt3/e;->f:Ljava/util/List;

    .line 143
    invoke-static {}, Lcom/fongmi/android/tv/bean/Live;->findAll()Ljava/util/List;

    .line 146
    move-result-object p2

    .line 147
    invoke-static {p2}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 150
    move-result-object p2

    .line 151
    new-instance v0, Lo7/a;

    .line 153
    const/16 v1, 0xa

    .line 155
    invoke-direct {v0, v1}, Lo7/a;-><init>(I)V

    .line 158
    invoke-static {}, Lj$/util/function/Function$-CC;->identity()Ljava/util/function/Function;

    .line 161
    move-result-object v1

    .line 162
    invoke-static {v0, v1}, Lj$/util/stream/Collectors;->toMap(Ljava/util/function/Function;Ljava/util/function/Function;)Lj$/util/stream/Collector;

    .line 165
    move-result-object v0

    .line 166
    invoke-interface {p2, v0}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 169
    move-result-object p2

    .line 170
    check-cast p2, Ljava/util/Map;

    .line 172
    invoke-virtual {p0}, Lt3/e;->n()Ljava/util/List;

    .line 175
    move-result-object v0

    .line 176
    new-instance v1, Lt3/c;

    .line 178
    const/4 v2, 0x0

    .line 179
    invoke-direct {v1, p2, v2}, Lt3/c;-><init>(Ljava/util/Map;I)V

    .line 182
    invoke-static {v0, v1}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 185
    invoke-virtual {p0}, Lt3/e;->n()Ljava/util/List;

    .line 188
    move-result-object p2

    .line 189
    invoke-interface {p2}, Ljava/util/List;->isEmpty()Z

    .line 192
    move-result p2

    .line 193
    const/4 v0, 0x0

    .line 194
    if-eqz p2, :cond_c9

    .line 196
    new-instance p2, Lcom/fongmi/android/tv/bean/Live;

    .line 198
    invoke-direct {p2}, Lcom/fongmi/android/tv/bean/Live;-><init>()V

    .line 201
    goto :goto_ef

    .line 202
    :cond_c9
    invoke-virtual {p0}, Lt3/e;->n()Ljava/util/List;

    .line 205
    move-result-object p2

    .line 206
    invoke-static {p2}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 209
    move-result-object p2

    .line 210
    new-instance v1, Lt3/b;

    .line 212
    const/4 v2, 0x0

    .line 213
    invoke-direct {v1, p1, v2}, Lt3/b;-><init>(Lcom/fongmi/android/tv/bean/Config;I)V

    .line 216
    invoke-interface {p2, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 219
    move-result-object p2

    .line 220
    invoke-interface {p2}, Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;

    .line 223
    move-result-object p2

    .line 224
    invoke-virtual {p0}, Lt3/e;->n()Ljava/util/List;

    .line 227
    move-result-object v1

    .line 228
    invoke-interface {v1, v0}, Ljava/util/List;->get(I)Ljava/lang/Object;

    .line 231
    move-result-object v1

    .line 232
    check-cast v1, Lcom/fongmi/android/tv/bean/Live;

    .line 234
    invoke-virtual {p2, v1}, Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;

    .line 237
    move-result-object p2

    .line 238
    check-cast p2, Lcom/fongmi/android/tv/bean/Live;

    .line 240
    :goto_ef
    invoke-virtual {p0, p1, p2, v0}, Lt3/e;->p(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Live;Z)V

    .line 243
    return-void
.end method

.method public final p(Lcom/fongmi/android/tv/bean/Config;Lcom/fongmi/android/tv/bean/Live;Z)V
    .registers 5

    .line 1
    iput-object p2, p0, Lt3/e;->e:Lcom/fongmi/android/tv/bean/Live;

    .line 3
    const/4 v0, 0x1

    .line 4
    invoke-virtual {p2, v0}, Lcom/fongmi/android/tv/bean/Live;->setActivated(Z)V

    .line 7
    iget-object p2, p0, Lt3/e;->e:Lcom/fongmi/android/tv/bean/Live;

    .line 9
    invoke-virtual {p2}, Lcom/fongmi/android/tv/bean/Live;->getName()Ljava/lang/String;

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
    invoke-virtual {p0}, Lt3/e;->n()Ljava/util/List;

    .line 24
    move-result-object p1

    .line 25
    new-instance p2, LF3/c;

    .line 27
    const/16 v0, 0x9

    .line 29
    invoke-direct {p2, p0, v0}, LF3/c;-><init>(Ljava/lang/Object;I)V

    .line 32
    invoke-static {p1, p2}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 35
    if-nez p3, :cond_48

    .line 37
    iget-object p1, p0, Lt3/e;->e:Lcom/fongmi/android/tv/bean/Live;

    .line 39
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Live;->isBoot()Z

    .line 42
    move-result p1

    .line 43
    const-string p2, "boot_live"

    .line 45
    if-nez p1, :cond_35

    .line 47
    const/4 p1, 0x0

    .line 48
    invoke-static {p2, p1}, LR6/g;->r(Ljava/lang/String;Z)Z

    .line 51
    move-result p1

    .line 52
    if-eqz p1, :cond_48

    .line 54
    :cond_35
    invoke-static {}, LN6/d;->b()LN6/d;

    .line 57
    move-result-object p1

    .line 58
    new-instance p3, Lz3/d;

    .line 60
    sget-object v0, Lz3/c;->q:Lz3/c;

    .line 62
    invoke-direct {p3, v0}, Lz3/d;-><init>(Lz3/c;)V

    .line 65
    invoke-virtual {p1, p3}, LN6/d;->e(Ljava/lang/Object;)V

    .line 68
    sget-object p1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;

    .line 70
    invoke-static {p1, p2}, LR6/g;->Q(Ljava/lang/Object;Ljava/lang/String;)V

    .line 73
    :cond_48
    return-void
.end method
