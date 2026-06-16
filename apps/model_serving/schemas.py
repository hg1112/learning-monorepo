from pydantic import BaseModel, Field, field_validator


class GenerateRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=500)
    negative_prompt: str = Field(default="", max_length=500)
    num_inference_steps: int = Field(default=20, ge=1, le=100)
    guidance_scale: float = Field(default=7.5, ge=1.0, le=20.0)
    width: int = Field(default=512, ge=64, le=1024)
    height: int = Field(default=512, ge=64, le=1024)
    seed: int | None = Field(default=None)

    @field_validator("width", "height")
    @classmethod
    def must_be_mutliple_of_8(cls, v):
        if v % 8 != 0:
            raise ValueError("width and height must be multiple of 8")
        return v


class GenerateResponse(BaseModel):
    image_b64: str
    seed_used: int
    inference_time_ms: float
