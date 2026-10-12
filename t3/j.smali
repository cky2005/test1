.class public abstract Lt3/j;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static volatile a:Lt3/k;


# direct methods
.method static constructor <clinit>()V
    .registers 1

    .line 1
    new-instance v0, Lt3/k;

    .line 3
    invoke-direct {v0}, Lt3/a;-><init>()V

    .line 6
    sput-object v0, Lt3/j;->a:Lt3/k;

    .line 8
    return-void
.end method
