import triton_python_backend_utils as pb_utils
import numpy as np

class TritonPythonModel:

    def initialize(self, args):
        pass

    def execute(self, requests):
        responses = []
        for request in requests:
            input_0 = pb_utils.get_input_tensor_by_name(request, "INPUT0").as_numpy()
            input_1 = pb_utils.get_input_tensor_by_name(request, "INPUT1").as_numpy()
            output_0 = np.add(input_0, input_1)
            out_tensor = pb_utils.Tensor("OUTPUT0", output_0)
            responses.append(pb_utils.InferenceResponse(output_tensors=[out_tensor]))
        return responses

    def finalize(self):
        pass