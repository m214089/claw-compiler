PROGRAM loop_opt
 INTEGER :: i
 INTEGER :: k
 INTEGER :: j
 INTEGER :: d
 INTEGER :: iend
 INTEGER :: jend
 INTEGER :: kend
 LOGICAL :: flag
 INTEGER :: sum

 iend = 20
 jend = 40
 kend = 30
 flag = .FALSE.
 DO j = 1 , jend , 1
  DO k = 1 , kend , 1
   d = d + 1
   sum = 0
  END DO
  DO i = 1 , iend , 1
   DO k = 1 , kend , 1
    sum = sum + i
    d = sum + d
   END DO
   IF ( flag ) THEN
    PRINT * ,"I did it"
   END IF
  END DO
 END DO
END PROGRAM loop_opt

