#time (
#  for i in 1 2 3 4; do
#    curl -s4 -X POST localhost:8000/v2/models/identity_model/infer \
#      -H "Content-Type: application/json" \
#      -d "{\"inputs\":[{\"name\":\"INPUT0\",\"shape\":[1,4],\"datatype\":\"FP32\",\"data\":[$i.0,$i.0,$i.0,$i.0]}],\"outputs\":[{\"name\":\"OUTPUT0\"}]}" &
#  done
#  wait
#)
#
#curl -s4 localhost:8000/v2/models/identity_model
#
#curl -s4 -X POST localhost:8000/v2/models/identity_model/versions/1/infer \
#  -H "Content-Type: application/json" \
#  -d '{"inputs":[{"name":"INPUT0","shape":[1,4],"datatype":"FP32","data":[1.0,1.0,1.0,1.0]}],"outputs":[{"name":"OUTPUT0"}]}'
#
#curl -s4 -X POST localhost:8000/v2/models/identity_model/versions/2/infer \
#-H "Content-Type: application/json" \
#-d '{"inputs":[{"name":"INPUT0","shape":[1,4],"datatype":"FP32","data":[1.0,1.0,1.0,1.0]}],"outputs":[{"name":"OUTPUT0"}]}'

curl -s4 -X POST localhost:8000/v2/models/python_add_model/infer \
  -H "Content-Type: application/json" \
  -d '{
    "inputs": [
      {"name": "INPUT0", "shape": [1, 4], "datatype": "FP32", "data": [1.0, 2.0, 3.0, 4.0]},
      {"name": "INPUT1", "shape": [1, 4], "datatype": "FP32", "data": [10.0, 20.0, 30.0, 40.0]}
    ],
    "outputs": [{"name": "OUTPUT0"}]
  }'

curl -s4 -X POST localhost:8000/v2/models/python_add_model/infer \
  -H "Content-Type: application/json" \
  -d '{
    "inputs": [
      {"name": "INPUT0", "shape": [1, 4], "datatype": "FP32", "data": [-1.0, -2.0, -3.0, -4.0]},
      {"name": "INPUT1", "shape": [1, 4], "datatype": "FP32", "data": [1.0, 2.0, 3.0, 4.0]}
    ],
    "outputs": [{"name": "OUTPUT0"}]
  }'