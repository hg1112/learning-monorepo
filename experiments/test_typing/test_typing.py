from typing import Callable, ParamSpec, TypeVar, Protocol

P = ParamSpec("P")
R = TypeVar("R")


class Remotable(Protocol[P, R]):

    def __call__(self, *args: P.args, **kwargs: P.kwargs) -> R: ...
    def remote(self, *args: P.args, **kwargs: P.kwargs) -> R: ...

def method(func: Callable[P, R]) -> Remotable[P, R]:
    def remote_impl(*args: P.args, **kwargs: P.kwargs) -> R:
        return func(*args, **kwargs)
    func.remote = remote_impl # type: ignore[attr-defined]
    return func # type: ignore[return-value]

@method
def f1(x: int, y: int) -> bool:
    return x > y

@method
def f2(x: int, y: int = 5) -> bool:
    return x > y

f1.remote(1, 2) # False
f1.remote(1)         # Error
f2.remote(1, 2) # False
f2.remote(1)          # False
