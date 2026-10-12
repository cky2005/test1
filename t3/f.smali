.class public abstract Lt3/f;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static volatile a:Li3/t;


# direct methods
.method static constructor <clinit>()V
    .registers 3

    .line 1
    new-instance v0, Li3/t;

    .line 3
    const/4 v1, 0x7

    .line 4
    const/4 v2, 0x0

    .line 5
    invoke-direct {v0, v1, v2}, Li3/t;-><init>(IZ)V

    .line 8
    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    .line 11
    move-result-object v1

    .line 12
    iput-object v1, v0, Li3/t;->o:Ljava/lang/Object;

    .line 14
    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    .line 17
    move-result-object v1

    .line 18
    iput-object v1, v0, Li3/t;->p:Ljava/lang/Object;

    .line 20
    sput-object v0, Lt3/f;->a:Li3/t;

    .line 22
    return-void
.end method
