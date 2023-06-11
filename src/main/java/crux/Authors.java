package crux;

final class Authors {
  // TODO: Add author information.
  static final Author[] all = {new Author("Joseph Prince", "60216525", "jdprince"),
                               new Author("Alexandra Zhang Jiang", "53188999", "azhangji")};
}


final class Author {
  final String name;
  final String studentId;
  final String uciNetId;

  Author(String name, String studentId, String uciNetId) {
    this.name = name;
    this.studentId = studentId;
    this.uciNetId = uciNetId;
  }
}
