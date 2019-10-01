"""
Usage:
  # From houndex/
  # Create label map from labels csv:
  python generate_label_map.py --labels_csv=data/train_labels.csv  --output_path=label_map.pbtxt
"""

import pandas as pd
import tensorflow as tf
from pathlib import Path
from object_detection.protos import string_int_label_map_pb2

from absl import app
from absl import flags

FLAGS = flags.FLAGS
flags.DEFINE_string('csv_input', '', 'Path to the labels CSV')
flags.DEFINE_string('output_path', '', 'Path to output label_map.pbtxt')

def generate_label_map(labels):
    num_classes = len(labels)
    label_map_proto = string_int_label_map_pb2.StringIntLabelMap()
    for i in range(num_classes):
      item = label_map_proto.item.add()
      item.id = i + 1
      item.name = labels[i]
      item.display_name = ' '.join(labels[i].split('_'))
    return str(label_map_proto)

def save_label_map(label_map_string, output_path):
    with tf.compat.v2.io.gfile.GFile(output_path, 'wb') as fid:
        fid.write(label_map_string)

def main(_):
  labels = pd.read_csv(FLAGS.csv_input)['class'].unique().tolist().sort()
  label_map_string = generate_label_map(labels)
  save_label_map(label_map_string, FLAGS.output_path)

if __name__ == '__main__':
    app.run(main)
