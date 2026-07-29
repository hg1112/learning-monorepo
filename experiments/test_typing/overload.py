from typing import overload, Any


class ActorMethodLike:

    @overload
    def remote(self, x: int, y: int) -> bool: ...

    @overload
    def remote(self, x: int) -> bool: ...

    def remote(self, x: int, y: Any = None) -> bool:
        return bool(x)

m = ActorMethodLike()
m.remote(1, 2) # True
m.remote(1)    # True
m.remote(1, y=2) #  True